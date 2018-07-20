/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import io.github.lukehutch.fastclasspathscanner.FailureHandler;
import io.github.lukehutch.fastclasspathscanner.ScanResultProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.ClassLoaderAndModuleFinder;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;
import io.github.lukehutch.fastclasspathscanner.utils.Recycler;
import io.github.lukehutch.fastclasspathscanner.utils.SingletonMap;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue.WorkQueuePreStartHook;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue.WorkUnitProcessor;

/** The classpath scanner. */
public class Scanner implements Callable<ScanResult> {
    private final ScanSpec scanSpec;
    private final ExecutorService executorService;
    private final int numParallelTasks;
    private final boolean enableRecursiveScanning;
    private final InterruptionChecker interruptionChecker = new InterruptionChecker();
    private final ScanResultProcessor scanResultProcessor;
    private final FailureHandler failureHandler;
    private final LogNode log;
    private NestedJarHandler nestedJarHandler;

    /**
     * The number of files within a given classpath element (directory or zipfile) to send in a chunk to the workers
     * that are calling the classfile binary parser. The smaller this number is, the better the load leveling at the
     * end of the scan, but the higher the overhead in re-opening the same ZipFile in different worker threads.
     */
    private static final int NUM_FILES_PER_CHUNK = 200;

    /** The classpath scanner. */
    public Scanner(final ScanSpec scanSpec, final ExecutorService executorService, final int numParallelTasks,
            final boolean enableRecursiveScanning, final ScanResultProcessor scannResultProcessor,
            final FailureHandler failureHandler, final LogNode log) {
        this.scanSpec = scanSpec;
        this.executorService = executorService;
        this.numParallelTasks = numParallelTasks;
        this.enableRecursiveScanning = enableRecursiveScanning;
        this.scanResultProcessor = scannResultProcessor;
        this.failureHandler = failureHandler;
        this.log = log;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A map from relative path to classpath element singleton. */
    private static class ClasspathElementPathToClasspathElementMap
            extends SingletonMap<ClasspathElementPath, ClasspathElement> implements AutoCloseable {
        private final boolean scanFiles;
        private final ScanSpec scanSpec;
        private final NestedJarHandler nestedJarHandler;
        private final InterruptionChecker interruptionChecker;
        private WorkQueue<ClasspathElementPath> workQueue;

        /** A map from relative path to classpath element singleton. */
        ClasspathElementPathToClasspathElementMap(final boolean scanFiles, final ScanSpec scanSpec,
                final NestedJarHandler nestedJarHandler, final InterruptionChecker interruptionChecker) {
            this.scanFiles = scanFiles;
            this.scanSpec = scanSpec;
            this.nestedJarHandler = nestedJarHandler;
            this.interruptionChecker = interruptionChecker;
        }

        /**
         * Work queue -- needs to be set for zipfiles, but not for directories, since zipfiles can contain
         * Class-Path manifest entries, which require the adding of additional work units to the scanning work
         * queue.
         */
        public void setWorkQueue(final WorkQueue<ClasspathElementPath> workQueue) {
            this.workQueue = workQueue;
        }

        /** Create a new classpath element singleton instance. */
        @Override
        public ClasspathElement newInstance(final ClasspathElementPath classpathElt, final LogNode log) {
            return ClasspathElement.newInstance(classpathElt, scanFiles, scanSpec, nestedJarHandler, workQueue,
                    interruptionChecker, log);
        }

        /** Close the classpath elements. */
        @Override
        public void close() throws Exception {
            for (final ClasspathElement classpathElt : values()) {
                classpathElt.close();
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private static void findClasspathOrder(final ClasspathElement currSingleton,
            final ClasspathElementPathToClasspathElementMap classpathElementMap,
            final HashSet<ClasspathElement> visitedClasspathElts, final ArrayList<ClasspathElement> order)
            throws InterruptedException {
        if (visitedClasspathElts.add(currSingleton)) {
            if (!currSingleton.skipClasspathElement) {
                // Don't add a classpath element, if it is marked to be skipped.
                order.add(currSingleton);
            }
            // Whether or not a classpath element should be skipped, add any child classpath elements that are
            // not marked to be skipped (i.e. keep recursing)
            if (currSingleton.childClasspathElts != null) {
                for (final ClasspathElementPath childClasspathElt : currSingleton.childClasspathElts) {
                    final ClasspathElement childSingleton = classpathElementMap.get(childClasspathElt);
                    if (childSingleton != null) {
                        findClasspathOrder(childSingleton, classpathElementMap, visitedClasspathElts, order);
                    }
                }
            }
        }
    }

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private static List<ClasspathElement> findClasspathOrder(final List<ClasspathElementPath> rawClasspathElements,
            final ClasspathElementPathToClasspathElementMap classpathElementMap) throws InterruptedException {
        // Recurse from toplevel classpath elements to determine a total ordering of classpath elements (jars with
        // Class-Path entries in their manifest file should have those child resources included in-place in the
        // classpath).
        final HashSet<ClasspathElement> visitedClasspathElts = new HashSet<>();
        final ArrayList<ClasspathElement> order = new ArrayList<>();
        for (final ClasspathElementPath toplevelClasspathElt : rawClasspathElements) {
            final ClasspathElement toplevelSingleton = classpathElementMap.get(toplevelClasspathElt);
            if (toplevelSingleton != null) {
                findClasspathOrder(toplevelSingleton, classpathElementMap, visitedClasspathElts, order);
            }
        }
        return order;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Holds range limits for chunks of classpath files that need to be scanned in a given classpath element.
     */
    private static class ClassfileParserChunk {
        private final ClasspathElement classpathElement;
        private final int classfileStartIdx;
        private final int classfileEndIdx;

        public ClassfileParserChunk(final ClasspathElement classpathElementSingleton, final int classfileStartIdx,
                final int classfileEndIdx) {
            this.classpathElement = classpathElementSingleton;
            this.classfileStartIdx = classfileStartIdx;
            this.classfileEndIdx = classfileEndIdx;
        }
    }

    /**
     * Break the classfiles that need to be scanned in each classpath element into chunks of approximately
     * NUM_FILES_PER_CHUNK files. This helps with load leveling so that the worker threads all complete their work
     * at approximately the same time.
     */
    private static List<ClassfileParserChunk> getClassfileParserChunks(
            final List<ClasspathElement> classpathOrder) {
        LinkedList<LinkedList<ClassfileParserChunk>> chunks = new LinkedList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final LinkedList<ClassfileParserChunk> chunksForClasspathElt = new LinkedList<>();
            final int numClassfileMatches = classpathElement.getNumClassfileMatches();
            if (numClassfileMatches > 0) {
                final int numChunks = (int) Math.ceil((float) numClassfileMatches / (float) NUM_FILES_PER_CHUNK);
                final float filesPerChunk = (float) numClassfileMatches / (float) numChunks;
                for (int i = 0; i < numChunks; i++) {
                    final int classfileStartIdx = (int) (i * filesPerChunk);
                    final int classfileEndIdx = i < numChunks - 1 ? (int) ((i + 1) * filesPerChunk)
                            : numClassfileMatches;
                    if (classfileEndIdx > classfileStartIdx) {
                        chunksForClasspathElt.add(
                                new ClassfileParserChunk(classpathElement, classfileStartIdx, classfileEndIdx));
                    }
                }
            }
            chunks.add(chunksForClasspathElt);
        }
        // There should be no overlap between the relative paths in any of the chunks, because classpath masking has
        // already been applied, so these chunks can be scanned in any order. But since a ZipFile instance can only
        // be used by one thread at a time, we want to space the chunks for a given ZipFile as far apart as possible
        // in the work queue to minimize the chance that two threads will try to open the same ZipFile at the same
        // time, as this will cause a second copy of the ZipFile to have to be opened by the ZipFile recycler. The
        // combination of chunking and interleaving therefore lets us achieve load leveling without work stealing or
        // other more complex mechanism.
        final List<ClassfileParserChunk> interleavedChunks = new ArrayList<>();
        while (!chunks.isEmpty()) {
            final LinkedList<LinkedList<ClassfileParserChunk>> nextChunks = new LinkedList<>();
            for (final LinkedList<ClassfileParserChunk> chunksForClasspathElt : chunks) {
                if (!chunksForClasspathElt.isEmpty()) {
                    final ClassfileParserChunk head = chunksForClasspathElt.remove();
                    interleavedChunks.add(head);
                    if (!chunksForClasspathElt.isEmpty()) {
                        nextChunks.add(chunksForClasspathElt);
                    }
                }
            }
            chunks = nextChunks;
        }
        return interleavedChunks;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine the unique ordered classpath elements, and run a scan looking for file or classfile matches if
     * necessary.
     */
    @Override
    public ScanResult call() throws InterruptedException, ExecutionException {
        final LogNode classpathFinderLog = log == null ? null : log.log("Finding classpath entries");
        this.nestedJarHandler = new NestedJarHandler(scanSpec, interruptionChecker, classpathFinderLog);
        try {
            final long scanStart = System.nanoTime();

            // Get classpath finder
            final LogNode getRawElementsLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Getting raw classpath elements");
            final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, nestedJarHandler,
                    getRawElementsLog);
            final ClassLoaderAndModuleFinder classLoaderAndModuleFinder = classpathFinder
                    .getClassLoaderAndModuleFinder();
            final ClassLoader[] classLoaderOrder = classLoaderAndModuleFinder.getClassLoaders();
            final List<ClasspathElementPath> rawClasspathEltOrder = new ArrayList<>();

            if (scanSpec.overrideClasspath == null && scanSpec.overrideClassLoaders == null) {
                // Add modules to start of classpath order (in JDK9+)
                final List<ModuleRef> systemModules = classLoaderAndModuleFinder.getSystemModuleRefs();
                if (systemModules != null) {
                    for (final ModuleRef systemModule : systemModules) {
                        final String moduleName = systemModule.getModuleName();
                        if (((!scanSpec.blacklistSystemJarsOrModules && scanSpec.whitelistedModules.isEmpty())
                                || scanSpec.whitelistedModules.contains(moduleName))
                                && !scanSpec.blacklistedModules.contains(moduleName)) {
                            rawClasspathEltOrder.add(
                                    new ClasspathElementPath(systemModule, nestedJarHandler, getRawElementsLog));
                        } else {
                            if (log != null) {
                                log.log("Skipping blacklisted/non-whitelisted system module: " + moduleName);
                            }
                        }
                    }
                }
                final List<ModuleRef> nonSystemModules = classLoaderAndModuleFinder.getNonSystemModuleRefs();
                if (nonSystemModules != null) {
                    for (final ModuleRef nonSystemModule : nonSystemModules) {
                        final String moduleName = nonSystemModule.getModuleName();
                        if ((scanSpec.whitelistedModules.isEmpty()
                                || scanSpec.whitelistedModules.contains(moduleName))
                                && !scanSpec.blacklistedModules.contains(moduleName)) {
                            rawClasspathEltOrder.add(
                                    new ClasspathElementPath(nonSystemModule, nestedJarHandler, getRawElementsLog));
                        } else {
                            if (log != null) {
                                log.log("Skipping blacklisted/non-whitelisted module: " + moduleName);
                            }
                        }
                    }
                }
            }

            // If there are no whitelisted modules, or the module whitelist contains the unnamed module
            if ((scanSpec.whitelistedModules.isEmpty() || scanSpec.whitelistedModules.contains(""))
                    && !scanSpec.blacklistedModules.contains("")) {
                // Add non-module classpath elements to classpath order
                rawClasspathEltOrder.addAll(classpathFinder.getRawClasspathElements());
            } else {
                if (log != null) {
                    log.log("Skipping non-module classpath entries, since the unnamed module (\"\") is not "
                            + "whitelisted, or is blacklisted");
                }
            }

            // In parallel, resolve raw classpath elements to canonical paths, creating a ClasspathElement singleton
            // for each unique canonical path. Also check jars against jar whitelist/blacklist.
            final LogNode preScanLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Reading jarfile metadata");
            final ClasspathElementPathToClasspathElementMap classpathElementMap = //
                    new ClasspathElementPathToClasspathElementMap(enableRecursiveScanning, scanSpec,
                            nestedJarHandler, interruptionChecker);
            WorkQueue.runWorkQueue(rawClasspathEltOrder, executorService, numParallelTasks,
                    new WorkUnitProcessor<ClasspathElementPath>() {
                        @Override
                        public void processWorkUnit(final ClasspathElementPath rawClasspathEltPath)
                                throws Exception {
                            // Check if classpath element is already in the singleton map -- saves needlessly
                            // repeating work in isValidClasspathElement() and createSingleton() (need to check for
                            // duplicates again, even though we checked above, since additonal classpath entries can
                            // come from Class-Path entries in manifests)
                            if (classpathElementMap.get(rawClasspathEltPath) != null) {
                                if (preScanLog != null) {
                                    preScanLog.log("Ignoring duplicate classpath element: " + rawClasspathEltPath);
                                }
                            } else if (rawClasspathEltPath.isValidClasspathElement(scanSpec, preScanLog)) {
                                try {
                                    final boolean isModule = rawClasspathEltPath.getModuleRef() != null;
                                    final boolean isFile = !isModule && rawClasspathEltPath.isFile(preScanLog);
                                    final boolean isDir = !isModule && rawClasspathEltPath.isDirectory(preScanLog);
                                    if (isModule) {
                                        // Scan all modules that were not already filtered out as system modules
                                        classpathElementMap.createSingleton(rawClasspathEltPath, preScanLog);
                                    } else if (isFile && !scanSpec.scanJars) {
                                        if (preScanLog != null) {
                                            preScanLog.log("Ignoring because jar scanning has been disabled: "
                                                    + rawClasspathEltPath);
                                        }
                                    } else if (isFile && !scanSpec
                                            .jarIsWhitelisted(rawClasspathEltPath.getCanonicalPath(preScanLog))) {
                                        if (preScanLog != null) {
                                            preScanLog
                                                    .log("Ignoring jarfile that is blacklisted or not whitelisted: "
                                                            + rawClasspathEltPath);
                                        }
                                    } else if (isDir && !scanSpec.scanDirs) {
                                        if (preScanLog != null) {
                                            preScanLog.log("Ignoring because directory scanning has been disabled: "
                                                    + rawClasspathEltPath);
                                        }
                                    } else {
                                        // Classpath element is valid, add as a singleton. This will trigger calling
                                        // the ClasspathElementZip constructor in the case of jarfiles, which will
                                        // check the manifest file for Class-Path entries, and if any are found,
                                        // additional work units will be added to the work queue to scan those
                                        // jarfiles too. If Class-Path entries are found, they are added as child
                                        // elements of the current classpath element, so that they can be inserted
                                        // at the correct location in the classpath order.
                                        classpathElementMap.createSingleton(rawClasspathEltPath, preScanLog);
                                    }
                                } catch (final Exception e) {
                                    if (preScanLog != null) {
                                        // Could not create singleton, probably due to path canonicalization problem
                                        preScanLog.log("Classpath element " + rawClasspathEltPath
                                                + " is not valid (" + e + ") -- skipping");
                                    }
                                }
                            }
                        }
                    }, new WorkQueuePreStartHook<ClasspathElementPath>() {
                        @Override
                        public void processWorkQueueRef(final WorkQueue<ClasspathElementPath> workQueue) {
                            // Store a ref back to the work queue in the classpath element map, because some
                            // classpath elements will need to schedule additional classpath elements for scanning,
                            // e.g. "Class-Path:" refs in jar manifest files
                            classpathElementMap.setWorkQueue(workQueue);
                        }
                    }, interruptionChecker, preScanLog);

            // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path
            // entries in-place into the ordering, if they haven't been listed earlier in the classpath already.
            final List<ClasspathElement> classpathOrder = findClasspathOrder(rawClasspathEltOrder,
                    classpathElementMap);

            // Print final classpath element order, after inserting Class-Path entries from manifest files
            if (classpathFinderLog != null) {
                final LogNode logNode = classpathFinderLog.log("Final classpath element order:");
                for (int i = 0; i < classpathOrder.size(); i++) {
                    final ClasspathElement classpathElt = classpathOrder.get(i);
                    final String packageRoot = classpathElt.getJarfilePackageRoot();
                    final ModuleRef classpathElementModuleRef = classpathElt.getClasspathElementModuleRef();
                    if (classpathElementModuleRef != null) {
                        logNode.log(i + ": module " + classpathElementModuleRef.getModuleName()
                                + " ; module location: " + classpathElementModuleRef.getModuleLocationStr());
                    } else {
                        final String classpathEltStr = classpathElt.toString();
                        final String classpathEltFileStr = "" + classpathElt.getClasspathElementFile(logNode);
                        logNode.log(i + ": " + (classpathEltStr.equals(classpathEltFileStr) && packageRoot.isEmpty()
                                ? classpathEltStr
                                : classpathElt + " -> " + classpathEltFileStr
                                        + (packageRoot.isEmpty() ? "" : " ; package root: " + packageRoot)));
                    }
                }
            }

            ScanResult scanResult;
            if (enableRecursiveScanning) {

                // Find classpath elements that are path prefixes of other classpath elements
                final List<SimpleEntry<String, ClasspathElement>> classpathEltResolvedPathToElement = //
                        new ArrayList<>();
                for (int i = 0; i < classpathOrder.size(); i++) {
                    final ClasspathElement classpathElement = classpathOrder.get(i);
                    classpathEltResolvedPathToElement.add(new SimpleEntry<>(
                            classpathElement.classpathEltPath.getResolvedPath(), classpathElement));
                }
                Collections.sort(classpathEltResolvedPathToElement,
                        new Comparator<SimpleEntry<String, ClasspathElement>>() {
                            // Sort classpath elements into lexicographic order
                            @Override
                            public int compare(final SimpleEntry<String, ClasspathElement> o1,
                                    final SimpleEntry<String, ClasspathElement> o2) {
                                // Path strings will all be unique
                                return o1.getKey().compareTo(o2.getKey());
                            }
                        });
                LogNode nestedClasspathRootNode = null;
                for (int i = 0; i < classpathEltResolvedPathToElement.size(); i++) {
                    // See if each classpath element is a prefix of any others (if so, they will immediately follow
                    // in lexicographic order)
                    final SimpleEntry<String, ClasspathElement> ei = classpathEltResolvedPathToElement.get(i);
                    final String basePath = ei.getKey();
                    final int basePathLen = basePath.length();
                    for (int j = i + 1; j < classpathEltResolvedPathToElement.size(); j++) {
                        final SimpleEntry<String, ClasspathElement> ej = classpathEltResolvedPathToElement.get(j);
                        final String comparePath = ej.getKey();
                        final int comparePathLen = comparePath.length();
                        boolean foundNestedClasspathRoot = false;
                        if (comparePath.startsWith(basePath) && comparePathLen > basePathLen) {
                            // Require a separator after the prefix
                            final char nextChar = comparePath.charAt(basePathLen);
                            if (nextChar == '/' || nextChar == '!') {
                                // basePath is a path prefix of comparePath. Ensure that the nested classpath does
                                // not contain another '!' zip-separator (since classpath scanning does not recurse
                                // to jars-within-jars unless they are explicitly listed on the classpath)
                                final String nestedClasspathRelativePath = comparePath.substring(basePathLen + 1);
                                if (nestedClasspathRelativePath.indexOf('!') < 0) {
                                    // Found a nested classpath root
                                    foundNestedClasspathRoot = true;
                                    // Store link from prefix element to nested elements
                                    final ClasspathElement baseElement = ei.getValue();
                                    if (baseElement.nestedClasspathRootPrefixes == null) {
                                        baseElement.nestedClasspathRootPrefixes = new ArrayList<>();
                                    }
                                    baseElement.nestedClasspathRootPrefixes.add(nestedClasspathRelativePath + "/");
                                    if (classpathFinderLog != null) {
                                        if (nestedClasspathRootNode == null) {
                                            nestedClasspathRootNode = classpathFinderLog
                                                    .log("Found nested classpath elements");
                                        }
                                        nestedClasspathRootNode.log(
                                                basePath + " is a prefix of the nested element " + comparePath);
                                    }
                                }
                            }
                        }
                        if (!foundNestedClasspathRoot) {
                            // After the first non-match, there can be no more prefix matches in the sorted order
                            break;
                        }
                    }
                }

                // Scan for matching classfiles / files, looking only at filenames / file paths, and not contents
                final LogNode pathScanLog = classpathFinderLog == null ? null
                        : classpathFinderLog.log("Scanning filenames within classpath elements");
                WorkQueue.runWorkQueue(classpathOrder, executorService, numParallelTasks,
                        new WorkUnitProcessor<ClasspathElement>() {
                            @Override
                            public void processWorkUnit(final ClasspathElement classpathElement) throws Exception {
                                // Scan the paths within a directory or jar
                                classpathElement.scanPaths(pathScanLog);
                            }
                        }, interruptionChecker, pathScanLog);

                // Implement classpath masking -- if the same relative classfile path occurs multiple times in the
                // classpath, ignore (remove) the second and subsequent occurrences. Note that classpath masking is
                // performed whether or not a jar is whitelisted, and whether or not jar or dir scanning is enabled,
                // in order to ensure that class references passed into MatchProcessors are the same as those that
                // would be loaded by standard classloading. (See bug #100.)
                final LogNode maskLog = log == null ? null : log.log("Masking classpath files");
                final HashSet<String> classpathRelativePathsFound = new HashSet<>();
                for (int classpathIdx = 0; classpathIdx < classpathOrder.size(); classpathIdx++) {
                    final ClasspathElement classpathElement = classpathOrder.get(classpathIdx);
                    classpathElement.maskFiles(classpathIdx, classpathRelativePathsFound, maskLog);
                }

                // Merge the maps from file to timestamp across all classpath elements (there will be no overlap in
                // keyspace, since file masking was already performed)
                final Map<File, Long> fileToLastModified = new HashMap<>();
                for (final ClasspathElement classpathElement : classpathOrder) {
                    fileToLastModified.putAll(classpathElement.fileToLastModified);
                }

                // Scan classfile binary headers in parallel
                final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked = //
                        new ConcurrentLinkedQueue<>();
                final LogNode classfileScanLog = log == null ? null : log.log("Scanning classfile binary headers");
                try (final Recycler<ClassfileBinaryParser, RuntimeException> classfileBinaryParserRecycler = //
                        new Recycler<ClassfileBinaryParser, RuntimeException>() {
                            @Override
                            public ClassfileBinaryParser newInstance() {
                                return new ClassfileBinaryParser();
                            }
                        }) {
                    final List<ClassfileParserChunk> classfileParserChunks = getClassfileParserChunks(
                            classpathOrder);
                    WorkQueue.runWorkQueue(classfileParserChunks, executorService, numParallelTasks,
                            new WorkUnitProcessor<ClassfileParserChunk>() {
                                @Override
                                public void processWorkUnit(final ClassfileParserChunk chunk) throws Exception {
                                    ClassfileBinaryParser classfileBinaryParser = null;
                                    try {
                                        classfileBinaryParser = classfileBinaryParserRecycler.acquire();
                                        chunk.classpathElement.parseClassfiles(classfileBinaryParser,
                                                chunk.classfileStartIdx, chunk.classfileEndIdx, classInfoUnlinked,
                                                classfileScanLog);
                                    } finally {
                                        classfileBinaryParserRecycler.release(classfileBinaryParser);
                                        classfileBinaryParser = null;
                                    }
                                }
                            }, interruptionChecker, classfileScanLog);
                }
                if (classfileScanLog != null) {
                    classfileScanLog.addElapsedTime();
                }

                // If we need to create a single custom classloader that can load all matched classes
                if (scanSpec.createClassLoaderForMatchingClasses) {
                    final LogNode classLoaderLog = classfileScanLog == null ? null
                            : classfileScanLog
                                    .log("Creating custom URLClassLoader for classpath elements containing "
                                            + "matching classes");

                    final Set<ClasspathElement> classpathElementsWithMatchedClasses = new HashSet<>();
                    for (final ClassInfoUnlinked c : classInfoUnlinked) {
                        classpathElementsWithMatchedClasses.add(c.classpathElement);
                    }
                    // Need to keep URLs in same relative order, but only include URLs for classpath elements
                    // that contained matched classes, for efficiency
                    final List<URL> urlOrder = new ArrayList<>(classpathOrder.size());
                    for (final ClasspathElement classpathElement : classpathOrder) {
                        if (classpathElementsWithMatchedClasses.contains(classpathElement)) {
                            try {
                                // Don't try to get classpath URL for modules (classloading from modules
                                // will be handled by parent classloader)
                                final ModuleRef modRef = classpathElement.getClasspathElementModuleRef();
                                if (modRef == null) {
                                    final File classpathEltFile = classpathElement.classpathEltPath
                                            .getFile(classLoaderLog);
                                    final URL url = classpathEltFile.toURI().toURL();
                                    urlOrder.add(url);
                                    if (classLoaderLog != null) {
                                        classLoaderLog.log(classpathElement + " -> " + url);
                                    }
                                }
                            } catch (final Exception e) {
                                if (classLoaderLog != null) {
                                    classLoaderLog
                                            .log("Cannot convert file to URL: " + classpathElement + " : " + e);
                                }
                            }
                        }
                    }
                    // Build a custom URLClassLoader that contains all the URLs for jars / package root dirs
                    // for matched classes
                    final ClassLoader parentClassLoader = classLoaderOrder == null || classLoaderOrder.length == 0
                            ? null
                            : classLoaderOrder[0];
                    @SuppressWarnings("resource")
                    final URLClassLoader customClassLoader = new URLClassLoader(
                            urlOrder.toArray(new URL[urlOrder.size()]), parentClassLoader);
                    // Replace the ClassLoaders in all classpath elements that contained matched classes
                    for (final ClasspathElement classpathElement : classpathElementsWithMatchedClasses) {
                        final ClassLoader[] oldClassLoaders = classpathElement.classpathEltPath.classLoaders;
                        // Prepend the new ClassLoader to the ClassLoader order (the older ClassLoaders should
                        // never be called, but we may as well leave them there in the array anyway)
                        final ClassLoader[] newClassLoaders = new ClassLoader[oldClassLoaders == null ? 1
                                : 1 + oldClassLoaders.length];
                        newClassLoaders[0] = customClassLoader;
                        if (oldClassLoaders != null) {
                            for (int i = 0; i < oldClassLoaders.length; i++) {
                                newClassLoaders[i + 1] = oldClassLoaders[i];
                            }
                        }
                        // Replace the classloaders for this classpath element with the new classloader order
                        classpathElement.classpathEltPath.classLoaders = newClassLoaders;
                    }
                }

                // Build the class graph: convert ClassInfoUnlinked to linked ClassInfo objects.
                final LogNode classGraphLog = log == null ? null : log.log("Building class graph");
                final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
                for (final ClassInfoUnlinked c : classInfoUnlinked) {
                    // Need to do two passes, so that annotation default parameter vals are available when linking
                    // non-attribute classes. In first pass, link annotations with default parameter vals.
                    if (c.annotationParamDefaultValues != null) {
                        c.link(scanSpec, classNameToClassInfo, classGraphLog);
                    }
                }
                for (final ClassInfoUnlinked c : classInfoUnlinked) {
                    // In second pass, link everything else.
                    if (c.annotationParamDefaultValues == null) {
                        // Create ClassInfo object from ClassInfoUnlinked object, and link into class graph
                        c.link(scanSpec, classNameToClassInfo, classGraphLog);
                    }
                }
                // Create ClassGraphBuilder
                if (classGraphLog != null) {
                    classGraphLog.addElapsedTime();
                }

                // Create ScanResult
                scanResult = new ScanResult(scanSpec, classpathOrder, classLoaderOrder, classNameToClassInfo,
                        fileToLastModified, nestedJarHandler, log);

            } else {
                // This is the result of a call to FastClasspathScanner#getUniqueClasspathElementsAsync(), so just
                // create placeholder ScanResult to contain classpathElementFilesOrdered.
                scanResult = new ScanResult(scanSpec, classpathOrder, classLoaderOrder,
                        /* classNameToClassInfo = */ null, /* fileToLastModified = */ null, nestedJarHandler, log);
            }
            if (log != null) {
                log.log("Completed scan", System.nanoTime() - scanStart);
            }

            // Run scanResultProcessor in the current thread
            if (scanResultProcessor != null) {
                try {
                    scanResultProcessor.processScanResult(scanResult);
                } catch (final Throwable e) {
                    throw new IllegalArgumentException("Exception while calling scan result processor", e);
                }
            }

            // Remove temporary files if necessary
            if (scanSpec.removeTemporaryFilesAfterScan) {
                scanResult.freeTempFiles(log);
            }

            // No exceptions were thrown -- return scan result
            return scanResult;

        } catch (final Throwable e) {
            // Remove temporary files if an exception was thrown
            if (this.nestedJarHandler != null) {
                this.nestedJarHandler.close(log);
            }
            if (log != null) {
                log.log(e);
            }
            if (failureHandler != null) {
                try {
                    failureHandler.onFailure(e);
                    // The return value is discarded when using a scanResultProcessor and failureHandler
                    return null;
                } catch (final Throwable t) {
                    throw new IllegalArgumentException("Exception while calling failure handler", t);
                }
            } else {
                throw e;
            }

        } finally {
            if (log != null) {
                log.flush();
            }
        }
    }
}
