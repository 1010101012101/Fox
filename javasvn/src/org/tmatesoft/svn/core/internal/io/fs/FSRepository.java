/*
 * ====================================================================
 * Copyright (c) 2004 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://tmate.org/svn/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.internal.io.fs;

import java.io.IOException;
import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.Set;

import org.tmatesoft.svn.core.ISVNDirEntryHandler;
import org.tmatesoft.svn.core.ISVNLogEntryHandler;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLock;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.io.SVNFileRevision;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.ISVNReporter;
import org.tmatesoft.svn.core.io.ISVNFileRevisionHandler;
import org.tmatesoft.svn.core.io.ISVNLocationEntryHandler;
import org.tmatesoft.svn.core.io.ISVNLockHandler;
import org.tmatesoft.svn.core.io.ISVNReporterBaton;
import org.tmatesoft.svn.core.io.ISVNSession;
import org.tmatesoft.svn.core.io.ISVNWorkspaceMediator;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.diff.SVNRAFileData;
import org.tmatesoft.svn.core.io.diff.SVNSequenceDeltaGenerator;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.util.SVNTimeUtil;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNRevisionProperty;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.io.SVNLocationEntry;

/**
 * @version 1.0
 * @author TMate Software Ltd.
 */
public class FSRepository extends SVNRepository implements ISVNReporter {

    private FileLock myDBSharedLock;
    private File myReposRootDir;
    // db.lock file representation for synchronizing
    private RandomAccessFile myDBLockFile;
    private FSReporterContext myReporterContext;// for reporter
    private FSRevisionNodePool myRevNodesPool;
    
    protected FSRepository(SVNURL location, ISVNSession options) {
        super(location, options);
        myRevNodesPool = new DefaultFSRevisionNodePool();
    }

    public void testConnection() throws SVNException {
        // try to open and close a repository
        try {
            openRepository();
        } finally {
            closeRepository();
        }
    }

    private void lockDBFile(File reposRootDir) throws SVNException {
        // 1. open db.lock for shared reading (?? just like in the svn code)
        File dbLockFile = FSRepositoryUtil.getDBLockFile(reposRootDir);

        if (!dbLockFile.exists()) {
            SVNErrorManager.error("svn: Error opening db lockfile" + SVNFileUtil.getNativeEOLMarker() + "svn: Can't open file '" + dbLockFile.getAbsolutePath() + "'");
        }

        myDBLockFile = null;
        try {
            myDBLockFile = new RandomAccessFile(dbLockFile, "r");
        } catch (FileNotFoundException fnfe) {
            SVNFileUtil.closeFile(myDBLockFile);
            SVNErrorManager.error("svn: Error opening db lockfile" + SVNFileUtil.getNativeEOLMarker() + "svn: Can't open file '" + dbLockFile.getAbsolutePath() + "': " + fnfe.getMessage());
        }

        // 2. lock db.lock blocking, not exclusively
        FileChannel fch = myDBLockFile.getChannel();
        try {
            myDBSharedLock = fch.lock(0, Long.MAX_VALUE, true);
        } catch (IOException ioe) {
            SVNFileUtil.closeFile(myDBLockFile);
            if (myDBSharedLock != null) {
                try {
                    myDBSharedLock.release();
                } catch (IOException ioex) {
                    //
                }
            }
            SVNErrorManager.error("svn: Error opening db lockfile" + SVNFileUtil.getNativeEOLMarker() + "svn: Can't get shared lock on file '" + dbLockFile.getAbsolutePath() + "': "
                    + ioe.getMessage());
        }
    }

    private void unlockDBFile() throws SVNException {
        // 1. release the shared lock
        if (myDBSharedLock != null) {
            try {
                myDBSharedLock.release();
            } catch (IOException ioe) {
                File dbLockFile = FSRepositoryUtil.getDBLockFile(myReposRootDir);
                SVNErrorManager.error("svn: Can't unlock file '" + dbLockFile.getAbsoluteFile() + "': " + ioe.getMessage());
            } finally {
                // 2. close 'db.lock' file
                SVNFileUtil.closeFile(myDBLockFile);
            }
        }
    }

    private void openRepository() throws SVNException {
        lock();
        String eol = SVNFileUtil.getNativeEOLMarker();
        String errorMessage = "svn: Unable to open an ra_local session to URL" + eol + "svn: Unable to open repository '" + getLocation() + "'";

        // Perform steps similar to svn's ones
        // 1. Find repos root
        try {
            myReposRootDir = FSRepositoryUtil.findRepositoryRoot(new File(getLocation().getPath()).getCanonicalFile());// findRepositoryRoot(getLocation().getPath());
        } catch (SVNException svne) {
            SVNErrorManager.error(errorMessage);
        } catch (IOException ioe) {
            SVNErrorManager.error(errorMessage + ": " + ioe.getMessage());
        }

        // 2. Check repos format (the format file must exist!)
        try {
            FSRepositoryUtil.checkRepositoryFormat(myReposRootDir);// checkReposFormat(myReposRootPath);
        } catch (SVNException svne) {
            SVNErrorManager.error(errorMessage + eol + svne.getMessage());
        }

        // 3. Lock 'db.lock' file non-exclusively, blocking, for reading only
        try {
            lockDBFile(myReposRootDir);
        } catch (SVNException svne) {
            SVNErrorManager.error(errorMessage + eol + svne.getMessage());
        }

        // 4. Check FS type for 'fsfs'
        try {
            FSRepositoryUtil.checkFSType(myReposRootDir);
        } catch (SVNException svne) {
            SVNErrorManager.error(errorMessage + eol + svne.getMessage());
        }

        // 5. Attempt to open the 'current' file of this repository
        File dbCurrentFile = FSRepositoryUtil.getFSCurrentFile(myReposRootDir);
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(dbCurrentFile);
        } catch (FileNotFoundException fnfe) {
            SVNErrorManager.error(errorMessage + eol + "svn: Can't open file '" + dbCurrentFile.getAbsolutePath() + "' " + fnfe.getMessage());
        } finally {
            SVNFileUtil.closeFile(fis);
        }

        /*
         * 6. Check the FS format number (db/format). Treat an absent format
         * file as format 1. Do not try to create the format file on the fly,
         * because the repository might be read-only for us, or we might have a
         * umask such that even if we did create the format file, subsequent
         * users would not be able to read it. See thread starting at
         * http://subversion.tigris.org/servlets/ReadMsg?list=dev&msgNo=97600
         * for more.
         */
        try {
            FSRepositoryUtil.checkFSFormat(myReposRootDir);
        } catch (SVNException svne) {
            SVNErrorManager.error(errorMessage + eol + svne.getMessage());
        }

        // 7. Read and cache repository UUID
        String uuid = null;
        try {
            uuid = FSRepositoryUtil.getRepositoryUUID(myReposRootDir);
        } catch (SVNException svne) {
            SVNErrorManager.error(errorMessage + eol + svne.getMessage());
        }
        String rootDir = null;
        try {
            rootDir = myReposRootDir.getCanonicalPath();
        } catch (IOException ioe) {
            SVNErrorManager.error("Can not convert path '" + myReposRootDir.getAbsolutePath() + "' to a canonical form");
            //rootDir = myReposRootDir.getAbsolutePath();
        }
        rootDir = rootDir.replace(File.separatorChar, '/');
        if (!rootDir.startsWith("/")) {
            rootDir = "/" + rootDir;
        }
        setRepositoryCredentials(uuid, SVNURL.parseURIEncoded(getLocation().getProtocol() + "://" + rootDir));
    }

    void closeRepository() throws SVNException {
        try {
            unlockDBFile();
            myRevNodesPool.clearAllCaches();
        } finally {
            unlock();
        }
    }

    public File getRepositoryRootDir() {
        return myReposRootDir;
    }

    long getYoungestRev(File reposRootDir) throws SVNException {
        File dbCurrentFile = FSRepositoryUtil.getFSCurrentFile(reposRootDir);
        String firstLine = FSReader.readSingleLine(dbCurrentFile);
        if (firstLine == null) {
            SVNErrorManager.error("svn: Can't read file '" + dbCurrentFile.getAbsolutePath() + "': End of file found");
        }
        String splittedLine[] = firstLine.split(" ");
        long latestRev = -1;
        try {
            latestRev = Long.parseLong(splittedLine[0]);
        } catch (NumberFormatException nfe) {
            // svn 1.2 will not report an error if there are no any digit bytes
            // but we decided to introduce this restriction
            SVNErrorManager.error("svn: Can't parse revision number in file '" + dbCurrentFile.getAbsolutePath() + "'");
        }
        return latestRev;
    }
    
    File getReposRootDir(){
        return myReposRootDir;
    }
    /*TODO delete 'public' since there were no such word before testing*/
    public FSRevisionNodePool getRevisionNodePool(){
        return myRevNodesPool;
    }
    
    public long getLatestRevision() throws SVNException {
        try {
            openRepository();
            return getYoungestRev(myReposRootDir);
        } finally {
            closeRepository();
        }
    }

    private Date getTime(File reposRootDir, long revision) throws SVNException {
        String timeString = null;
        timeString = FSRepositoryUtil.getRevisionProperty(reposRootDir, revision, SVNRevisionProperty.DATE);
        if (timeString == null) {
            SVNErrorManager.error("svn: Failed to find time on revision " + revision);
        }
        Date date = null;
        date = SVNTimeUtil.parseDate(timeString);
        if (date == null) {
            SVNErrorManager.error("svn: Can't parse date on revision " + revision);
        }
        return date;
    }

    private long getDatedRev(File reposRootDir, Date date) throws SVNException {
        long latestRev = getYoungestRev(reposRootDir);
        long topRev = latestRev;
        long botRev = 0;
        long midRev;
        Date curTime = null;

        while (botRev <= topRev) {
            midRev = (topRev + botRev) / 2;
            curTime = getTime(reposRootDir, midRev);
            if (curTime.compareTo(date) > 0) {// overshot
                if ((midRev - 1) < 0) {
                    return 0;
                }
                Date prevTime = getTime(reposRootDir, midRev - 1);
                // see if time falls between midRev and midRev-1:
                if (prevTime.compareTo(date) < 0) {
                    return midRev - 1;
                }
                topRev = midRev - 1;
            } else if (curTime.compareTo(date) < 0) {// undershot
                if ((midRev + 1) > latestRev) {
                    return latestRev;
                }
                Date nextTime = getTime(reposRootDir, midRev + 1);
                // see if time falls between midRev and midRev+1:
                if (nextTime.compareTo(date) > 0) {
                    return midRev + 1;
                }
                botRev = midRev + 1;
            } else {
                return midRev;// exact match!
            }
        }
        return 0;
    }

    public long getDatedRevision(Date date) throws SVNException {
        if (date == null) {
            date = new Date(System.currentTimeMillis());
        }
        try {
            openRepository();
            return getDatedRev(myReposRootDir, date);
        } finally {
            closeRepository();
        }
    }

    public Map getRevisionProperties(long revision, Map properties) throws SVNException {
        assertValidRevision(revision);
        try {
            openRepository();
            Map revProps = FSRepositoryUtil.getRevisionProperties(myReposRootDir, revision);
            if (properties == null) {
                properties = revProps;
            }
        } finally {
            closeRepository();
        }
        return properties;
    }

    public void setRevisionPropertyValue(long revision, String propertyName, String propertyValue) throws SVNException {
        assertValidRevision(revision);
        try {
            openRepository();

            if (!SVNProperty.isRegularProperty(propertyName)) {
                SVNErrorManager.error("svn: Storage of non-regular property '" + propertyName + "' is disallowed through the repository interface, and could indicate a bug in your client");
            }
            String userName = System.getProperty("user.name");
            String oldValue = FSRepositoryUtil.getRevisionProperty(myReposRootDir, revision, propertyName);
            String action = null;
            if (propertyValue == null) {// delete
                action = FSHooks.REVPROP_DELETE;
            } else if (oldValue == null) {// add
                action = FSHooks.REVPROP_ADD;
            } else {// modify
                action = FSHooks.REVPROP_MODIFY;
            }
            FSWriter.setRevisionProperty(myReposRootDir, revision, propertyName, propertyValue, oldValue, userName, action);
        } finally {
            closeRepository();
        }
    }

    public String getRevisionPropertyValue(long revision, String propertyName) throws SVNException {
        assertValidRevision(revision);
        if (propertyName == null) {
            return null;
        }
        try {
            openRepository();
            return FSRepositoryUtil.getRevisionProperty(myReposRootDir, revision, propertyName);
        } finally {
            closeRepository();
        }
    }

    public SVNNodeKind checkPath(String path, long revision) throws SVNException {
        try {
            openRepository();
            if (!SVNRepository.isValidRevision(revision)) {
                revision = getYoungestRev(myReposRootDir);
            }
            path = path == null ? "" : path;
            String repositoryPath = getRepositoryPath(path);
            return checkNodeKind(repositoryPath, null, revision);
        } finally {
            closeRepository();
        }
    }

    SVNNodeKind checkNodeKind(String repositoryPath, FSRoot root, long revision) throws SVNException {
        FSRevisionNode revNode = root == null ? myRevNodesPool.getRevisionNode(revision, repositoryPath, myReposRootDir) : myRevNodesPool.getRevisionNode(root, repositoryPath, myReposRootDir);
        return revNode == null ? SVNNodeKind.NONE : revNode.getType();
    }

    public long getFile(String path, long revision, Map properties, OutputStream contents) throws SVNException {
        try {
            openRepository();
            if (!SVNRepository.isValidRevision(revision)) {
                revision = getYoungestRev(myReposRootDir);
            }
            path = path == null ? "" : path;
            String repositoryPath = getRepositoryPath(path);
            FSRevisionNode revNode = myRevNodesPool.getRevisionNode(revision, repositoryPath, myReposRootDir);
            if (revNode == null) {
                SVNErrorManager.error("svn: Attempted to open non-existent child node '" + path + "'");
            } else if (revNode.getType() != SVNNodeKind.FILE) {
                SVNErrorManager.error("svn: Path at '" + path + "' is not a file, but " + revNode.getType());
            }
            FSReader.getFileContents(revNode, contents, myReposRootDir);
            if (properties != null) {
                properties.putAll(collectProperties(revNode, myReposRootDir));
            }
            return revision;
        } finally {
            closeRepository();
        }
    }
    
    // path is relative to this FSRepository's location
    private Collection getDirEntries(FSRevisionNode parent, File reposRootDir, Map parentDirProps, boolean includeLogs) throws SVNException {
        Map entries = FSReader.getDirEntries(parent, reposRootDir);
        Set keys = entries.keySet();
        Iterator dirEntries = keys.iterator();
        Collection dirEntriesList = new LinkedList();

        while (dirEntries.hasNext()) {
            String name = (String) dirEntries.next();
            FSEntry repEntry = (FSEntry) entries.get(name);
            if (repEntry != null) {
                dirEntriesList.add(buildDirEntry(repEntry, null, reposRootDir, includeLogs));
            }
        }
        if (parentDirProps != null) {
            parentDirProps.putAll(collectProperties(parent, reposRootDir));
        }
        return dirEntriesList;
    }

    private Map collectProperties(FSRevisionNode revNode, File reposRootDir) throws SVNException {
        Map properties = new HashMap();
        // first fetch out user props
        Map versionedProps = FSReader.getProperties(revNode, reposRootDir);
        if (versionedProps != null && versionedProps.size() > 0) {
            properties.putAll(versionedProps);
        }
        // now add special non-tweakable metadata props
        Map metaprops = null;
        try {
            metaprops = FSRepositoryUtil.getMetaProps(reposRootDir, revNode.getId().getRevision(), this);
        } catch (SVNException svne) {
            //
        }
        if (metaprops != null && metaprops.size() > 0) {
            properties.putAll(metaprops);
        }
        return properties;
    }
	
    private SVNDirEntry buildDirEntry(FSEntry repEntry, FSRevisionNode revNode, File reposRootDir, boolean includeLogs) throws SVNException {
        FSRevisionNode entryNode = revNode == null ? FSReader.getRevNodeFromID(reposRootDir, repEntry.getId()) : revNode;

        // dir size is equated to 0
        long size = 0;

        if (entryNode.getType() == SVNNodeKind.FILE) {
            size = entryNode.getTextRepresentation().getExpandedSize();
        }

        Map props = null;
        props = FSReader.getProperties(entryNode, reposRootDir);
        boolean hasProps = (props == null || props.size() == 0) ? false : true;

        // should it be an exception if getting a rev property is impossible,
        // hmmm?
        Map revProps = null;
        try {
            revProps = FSRepositoryUtil.getRevisionProperties(reposRootDir, repEntry.getId().getRevision());
        } catch (SVNException svne) {
            //
        }

        String lastAuthor = null;
        String log = null;
        if (revProps != null && revProps.size() > 0) {
            lastAuthor = (String) revProps.get(SVNRevisionProperty.AUTHOR);
            log = (String) revProps.get(SVNRevisionProperty.LOG);
        }

        Date lastCommitDate = null;
        try {
            lastCommitDate = getTime(reposRootDir, repEntry.getId().getRevision());
        } catch (SVNException svne) {
            //
        }

        return new SVNDirEntry(repEntry.getName(), repEntry.getType(), size, hasProps, repEntry.getId().getRevision(), lastCommitDate, lastAuthor, includeLogs ? log : null);
    }

    public long getDir(String path, long revision, Map properties, ISVNDirEntryHandler handler) throws SVNException {
        try {
            openRepository();
            if (!SVNRepository.isValidRevision(revision)) {
                revision = getYoungestRev(myReposRootDir);
            }
            path = path == null ? "" : path;
            String repositoryPath = getRepositoryPath(path);
            FSRevisionNode parent = myRevNodesPool.getRevisionNode(revision, repositoryPath, myReposRootDir);
            if (parent == null) {
                SVNErrorManager.error("svn: Attempted to open non-existent child node '" + path + "'" + SVNFileUtil.getNativeEOLMarker() + "svn: File not found: revision " + revision + ", path '"
                        + getRepositoryPath(path) + "'");
            }
            Collection entriesCollection = getDirEntries(parent, myReposRootDir, properties, false);
            Iterator entries = entriesCollection.iterator();
            while (entries.hasNext()) {
                SVNDirEntry entry = (SVNDirEntry) entries.next();
                handler.handleDirEntry(entry);
            }
            return revision;
        } finally {
            closeRepository();
        }
    }

    public SVNDirEntry getDir(String path, long revision, boolean includeCommitMessages, Collection entries) throws SVNException {
        try {
            openRepository();
            if (!SVNRepository.isValidRevision(revision)) {
                revision = getYoungestRev(myReposRootDir);
            }
            path = path == null ? "" : path;
            String repositoryPath = getRepositoryPath(path);

            FSRevisionNode parent = myRevNodesPool.getRevisionNode(revision, repositoryPath, myReposRootDir);
            if (parent == null) {
                SVNErrorManager.error("svn: Attempted to open non-existent child node '" + path + "'" + SVNFileUtil.getNativeEOLMarker() + "svn: File not found: revision " + revision + ", path '"
                        + getRepositoryPath(path) + "'");
            }
            entries.addAll(getDirEntries(parent, myReposRootDir, null, includeCommitMessages));
            SVNDirEntry parentDirEntry = buildDirEntry(new FSEntry(parent.getId(), parent.getType(), ""), parent, myReposRootDir, false);
            parentDirEntry.setPath(parent.getCreatedPath());
            return parentDirEntry;
        } finally {
            closeRepository();
        }
    }

    public int getFileRevisions(String path, long startRevision, long endRevision, ISVNFileRevisionHandler handler) throws SVNException {
		int counter = 0;
    	try{
    		openRepository();
    		path = path == null ? "" : path;
    		String repositoryPath = super.getRepositoryPath(path);    		
            String parentPath = SVNPathUtil.removeTail(repositoryPath);
            //suppose parentPath to be full path of repository's file at local machine
            ArrayList fileRevs = new ArrayList(0);
            ArrayList revNums = new ArrayList(0);
            ArrayList revPaths = new ArrayList(0);
            FSRevisionNode root = FSReader.getRootRevNode(myReposRootDir, endRevision);
            SVNNodeKind kind = this.checkPath(parentPath, endRevision);
            if(kind != SVNNodeKind.FILE){
            	SVNErrorManager.error(parentPath + " is not a file");
            }
            FSNodeHistory history = FSNodeHistory.getNodeHistory(myReposRootDir, root, parentPath);
            //get revisions we are interested in
            while(true){
            	history = history.fsHistoryPrev(myReposRootDir, /*history,*/ true, myRevNodesPool);            	
            	if(history == null){
            		break;
            	}
            	SVNLocationEntry revEntry = history.getHistoryEntry();
            	revNums.add(new Long(revEntry.getRevision()));
            	revPaths.add(revEntry.getPath());
            	if(revEntry.getRevision() <= startRevision){
            		break;
            	}
            }
            //this time there must be at least one revision
            if(revNums.size() <= 0){
            	//it is used for debugging only, svn's string is assert()
            	SVNErrorManager.error("No elements in revision numbers array");
            }
            for(int count = revNums.size(); count > 0; count--){
                Map lastProps = new HashMap();
                //FSRevisionNode lastRoot = null;
                //String lastPath = null;
            	long rev = ((Long)revNums.get(count-1)).longValue();
            	String revPath = (String)revPaths.get(count-1);
            	//Get revision properties
            	Map revProps = FSRepositoryUtil.getRevisionProperties(myReposRootDir, rev);
                //Open the revision root
            	root = FSReader.getRootRevNode(myReposRootDir, rev);
            	//Get the file's properties for this revision and compute the diffs
            	FSRevisionNode revNode = FSReader.getRevisionNode(myReposRootDir, revPath, root, 0);
            	Map props = FSReader.getProperties(revNode, myReposRootDir);
            	Map propDiffs = FSRepositoryUtil.getPropsDiffs(props, lastProps);
                //Check if the contents changed. */
                //Special case: In the first revision, we always provide a delta. */
            	//boolean contentsChanged = false;
            	//if(lastRoot != null){
            	//	contentsChanged = areFileContentsChanged(lastRoot, lastPath, root, revPath);
            	//}else{
            	//	contentsChanged = true;
            	//}
            	//We have all we need just add it to array            	            	
            	fileRevs.add(new SVNFileRevision(revPath, rev, revProps, propDiffs));
            	
            	//there is functionality provide sending delta, if user wants
            	//but c-code of svn never execute this piece if code
            	
                //Remember root, path and props for next iteration        	
            	//lastRoot = root;
            	//lastPath = revPath;
            	lastProps = props;
            }
            //invoke handler
            for(counter = 0; counter < fileRevs.size(); counter++){
            	handler.handleFileRevision((SVNFileRevision)fileRevs.get(counter));
            }
    	}catch(SVNException ex){
    		SVNErrorManager.error(null);
    	}
        finally{
            closeRepository();
        }
        return counter;
    }

    /*TODO check the correctness of existing code*/
    public long log(String[] targetPaths, long startRevision, long endRevision, boolean discoverChangedPath, boolean strictNode, long limit, ISVNLogEntryHandler handler) throws SVNException {
    	try{
    		openRepository();
        	ArrayList absPaths = new ArrayList(0);
        	if(targetPaths != null){
        		for(int count = 0; count < targetPaths.length; count++){
        			absPaths.add(SVNPathUtil.concatToAbs("", targetPaths[count]));
        		}
        	}
        	long headRevision = getYoungestRev(myReposRootDir);
        	long histStart = startRevision;
        	long histEnd = endRevision;
        	if(SVNRepository.isInvalidRevision(startRevision)){
        		startRevision = headRevision;
        	}
        	if(SVNRepository.isInvalidRevision(endRevision)){
        		endRevision = headRevision;
        	}
        	//Check that revisions are sane before ever invoking receiver
        	if(startRevision > headRevision){
        		SVNErrorManager.error("No such revision " + new Long(startRevision));
        	}
        	if(endRevision > headRevision){
        		SVNErrorManager.error("No such revision" + new Long(endRevision));
        	}
        	//Get an ordered copy of the start and end
        	if(startRevision > endRevision){
        		histStart = startRevision;
        		histEnd = endRevision;
        	}        	
       	    
        	/* If paths were specified, then we only really care about revisions
            *  in which those paths were changed.  So we ask the filesystem for
            *  all the revisions in which any of the paths was changed.
            *  
            *  SPECIAL CASE: If we were given only path, and that path is empty,
            *  then the results are the same as if we were passed no paths at
            *  all.  Why?  Because the answer to the question "In which
            *  revisions was the root of the filesystem changed?" is always
            *  "Every single one of them."  And since this section of code is
            *  only about answering that question, and we already know the
            *  answer ... well, you get the picture.*/
        	long sendCount = 0;
        	if(absPaths == null || (absPaths.size() == 1 && "".equals(absPaths.get(0)))){
        		sendCount = histEnd - histStart + 1;
        		if(limit != 0 && sendCount > limit){
        			sendCount = limit;
        		}
        		for(int count = 0; count < sendCount; count++){
        			long rev = histStart + count;
        			if(startRevision > endRevision){
        				rev = histEnd + count;
        			}        			
        			sendChangeRev(rev, discoverChangedPath, handler);
        		}
        	}
        	ArrayList histories = new ArrayList(0);
        	for(int count = 0; count < absPaths.size(); count++){
        		String thisPath = (String)absPaths.get(count);
        		FSRevisionNode root = myRevNodesPool.getRootRevisionNode(histEnd, myReposRootDir);        		
        		LogPathInfo info = new LogPathInfo(root, thisPath, FSNodeHistory.getNodeHistory(myReposRootDir, root, thisPath), FSConstants.SVN_INVALID_REVNUM);
        		info.pickUpNextHistory(myReposRootDir, strictNode, histStart);
        		histories.add(info);
        	}
        	/*TODO check correctness of following code*/
        	/*!!!!svn implementation is not good desision*/
        	boolean anyHistLeft = true;
        	ArrayList revsArr = null;
        	for(long current = histEnd; current >= histStart && anyHistLeft; ){        		
        		long tempRev = FSConstants.SVN_INVALID_REVNUM;
        		boolean changed = false;
        		anyHistLeft = false;
        		for(int count = 0; count < histories.size(); count++){        			
        			LogPathInfo info = ((LogPathInfo)histories.get(count));
        			if(info.getHistory() == null){
        				continue;
        			}
        			if(info.getHistoryRevision() > tempRev){
        				tempRev = info.getHistoryRevision();        				
        			}        			
        		}        		
        		current = tempRev;
        		for(int count = 0; count < histories.size(); count++){
        			LogPathInfo info = ((LogPathInfo)histories.get(count));
        	        /* Check history for this path in current rev. */
        			changed = info.checkHistory(myReposRootDir, current, strictNode, histStart);
        			if(info.getHistory() == null){
        				anyHistLeft = true;
        			}
        		}
        		if(changed == true){
        			if(startRevision > endRevision){        				
        				sendChangeRev(current, discoverChangedPath, handler);
        				if(limit != 0 && ++sendCount >= limit){
        					break;
        				}
        			}else
        			{
        				if(revsArr == null){
        					revsArr = new ArrayList(0);
        				}	
        				revsArr.add(new Long(current));
        			}
        		}
        	}
        	if(revsArr != null){
        		for(int count = 0; count < revsArr.size(); count++){        			
        			sendChangeRev(((Long)revsArr.get(revsArr.size() - count - 1)).longValue(), discoverChangedPath, handler);
        			if(limit != 0 && count + 1 >= limit){
        				break;
        			}
        		}
        	}
    	}catch(SVNException ex){
    		SVNErrorManager.error(null);
    	}finally{
    		closeRepository();
    	}    	   	
        return 0;
    }
    
    /* Pass history information about REV to RECEIVER.
    * FS is used with REV to fetch the interesting history information,
    * such as author, date, etc.
    * The detectChanged() function if DISCOVER_CHANGED_PATHS is TRUE.  See it for details.
    */
    private void sendChangeRev(long revNum, boolean discoverChangedPath, ISVNLogEntryHandler handler)throws SVNException{    	
    	Map rProps = FSRepositoryUtil.getRevisionProperties(myReposRootDir, revNum);
    	Map changedPaths = null;
    	String author = (String)rProps.get(SVNRevisionProperty.AUTHOR);
    	Date date = (Date)rProps.get(SVNRevisionProperty.DATE);
    	String message = (String)rProps.get(SVNRevisionProperty.LOG);
    	
   	   /* Discover changed paths if the user requested them
        * or if we need to check that they are readable
        */
    	if(revNum > 0 && discoverChangedPath == true){
    		FSRevisionNode newRoot = FSReader.getRootRevNode(myReposRootDir, revNum);
    		changedPaths = detectChanged(new FSRoot(revNum, newRoot));
    	}
        if(discoverChangedPath == false){
            changedPaths = null;
        }
    	handler.handleLogEntry(new SVNLogEntry(changedPaths, revNum, author, date, message));
    }
    
    /* Store as keys in returned Map the paths of all node in ROOT that show a
     * significant change.  "Significant" means that the text or
     * properties of the node were changed, or that the node was added or
     * deleted.
     * Keys are String paths and values are FSLogChangedPath.
     */
    private Map detectChanged(FSRoot root)throws SVNException{
        Map returnChanged = new HashMap();
        Map changes = getFSpathChanged(root);        
        if(changes.size() == 0){
            return changes;
        }
        Set hashKeys = changes.keySet();
        Iterator chgIter = hashKeys.iterator();
        while(chgIter.hasNext()){
            char action;
            String hashPathKey = (String)chgIter.next();
            FSPathChange change = (FSPathChange)changes.get(hashPathKey);
            String path = hashPathKey;                      
            
            switch(change.getChangeKind().intValue()){
                case 4 /*FS_PATH_CHANGE_RESET*/:
                continue;
                case 1 /*FS_PATH_CHANGE_ADD*/:
                    action = 'A';
                    break;
                case 2 /*FS_PATH_CHANGE_DELETE*/:
                    action = 'D';
                    break;
                case 3 /*FS_PATH_CHANGE_REPLACE*/:
                    action = 'R';
                    break;
                case 0 /*FS_PATH_CHANGE_MODIFY*/:
                default:
                    action = 'M';
                    break;                    
            }
            FSLogChangedPath itemCopyfrom = new FSLogChangedPath(action, new SVNLocationEntry(FSConstants.SVN_INVALID_REVNUM, null));
            if(action == 'A' || action == 'R'){                                
                SVNLocationEntry copyfromEntry = copiedFrom(myReposRootDir, root.getRootRevisionNode(), path, myRevNodesPool);
                if(copyfromEntry.getPath() != null && FSRepository.isValidRevision(copyfromEntry.getRevision())){
                    itemCopyfrom = new FSLogChangedPath(action, copyfromEntry);
                }                
            }
            returnChanged.put(path, itemCopyfrom);
        }
        return returnChanged;
    }
   
    /* Return MAP with hash containing descriptions of the paths changed under ROOT. 
     * The hash is keyed with String paths and has FSPathChange values
     */    
    private Map getFSpathChanged(FSRoot root)throws SVNException{   
        Map changedPaths = new HashMap();
        if(root.isTxnRoot() == true){
            File txnFile = new File(myReposRootDir.getAbsoluteFile() + 
                    FSConstants.SVN_REPOS_TXNS_DIR + root.getTxnId() + 
                    FSConstants.TXN_PATH_EXT + 
                    FSConstants.TXN_PATH_CHANGES);
            ArrayList fetchArr = fetchAllChanges(changedPaths, txnFile, false, 0, root.getCopyfromCache());
            root.setCopyfromCache((Map)fetchArr.get(1));
            return (Map)fetchArr.get(0);
    	}           
        long changeOffset = FSReader.getChangesOffset(myReposRootDir, root.getRevision());
        ArrayList fetchArr = fetchAllChanges(changedPaths, new File(myReposRootDir.getAbsoluteFile() + "/" + FSConstants.SVN_REPOS_REVS_DIR + "/" + root.getRevision()), true, changeOffset, root.getCopyfromCache());    
        root.setCopyfromCache((Map)fetchArr.get(1));
        return (Map)fetchArr.get(0);    	
    }
    
    /*Return ArrayList consist of two Maps:
     * ArrayList[0]: pathChanged Map
     * ArrayList[1]: copyfromCache Map*/    
    private static ArrayList fetchAllChanges(Map changedPaths, File revFile, boolean prefolded, long offsetToFirstChanges, Map mapCopyfrom)throws SVNException{        
        InputStream inputStream = SVNFileUtil.openFileForReading(revFile);
        if (inputStream == null) {
            SVNErrorManager.error("svn: Can't open file '" + revFile.getAbsolutePath() + "'");
        }        
        RandomAccessFile raReader = SVNFileUtil.openRAFileForReading(revFile);
        
        Map internalMapChangedPath = changedPaths != null ? changedPaths : new HashMap();  
        Map internalMapCopyfrom = mapCopyfrom != null ? mapCopyfrom : new HashMap();
        FSChange change = FSReader.readChanges(revFile, raReader, offsetToFirstChanges, true);        
        while(change != null){
            ArrayList retArr = foldChange(internalMapChangedPath, change, internalMapCopyfrom);
            internalMapChangedPath = (Map)retArr.get(0);
            internalMapCopyfrom = (Map)retArr.get(1);
            
            if( ( FSPathChangeKind.FS_PATH_CHANGE_DELETE.equals(change.getKind()) || 
                    FSPathChangeKind.FS_PATH_CHANGE_REPLACE.equals(change.getKind()) ) && 
                    prefolded == false){
                                
                Collection keySet = internalMapChangedPath.keySet();
                Iterator curIter = keySet.iterator();
                while(curIter.hasNext()){
                    String hashKeyPath = (String)curIter.next();
                    /*If we come across our own path, ignore it*/                    
                    if(change.getPath().equals(hashKeyPath)){
                        continue;
                    }
                    /*If we come across a child of our path, remove it*/
                    if(SVNPathUtil.pathIsChild(change.getPath(), hashKeyPath) != null){
                        internalMapChangedPath.remove(hashKeyPath);
                    }
                }
            }
            change = FSReader.readChanges(revFile, raReader, 0, false);
        }
        try{
            inputStream.close();
        }catch(IOException ex){
            SVNErrorManager.error("Can't close InputStream for '" + revFile.getAbsolutePath() + "' file" );
        }
        
        ArrayList retArr = new ArrayList(0);
        retArr.add(internalMapChangedPath);
        retArr.add(internalMapCopyfrom);
        
        return retArr;        
    }
    
    /* Merge the internal-use-only FSChange into a hash of FSPathChanges, 
     * collapsing multiple changes into a single summarising change per path.  
     * Also keep copyfromCache (here it is a parameter Map mapCopyfrom) up to date with new adds and replaces */
    private static ArrayList foldChange(Map mapChanges, FSChange change, Map mapCopyfrom)throws SVNException{
        if(mapChanges == null || change == null){
            return null;            
        }
        Map internalMapChanges = mapChanges != null ? mapChanges : new HashMap();
        Map internalMapCopyfrom = new HashMap(mapCopyfrom);
        FSPathChange oldChange = null;
        FSPathChange newChange = null;
        SVNLocationEntry copyfromEntry = null;
        String copyfromPath = null;
        String path = null;
        
        if((oldChange = (FSPathChange)internalMapChanges.get(change.getPath())) != null){
            /* Get the existing copyfrom entry for this path. */
            copyfromEntry = (SVNLocationEntry)internalMapCopyfrom.get(change.getPath());
            if(copyfromEntry != null){
                copyfromPath = change.getPath();
            }
            path = change.getPath();
            /* Sanity check:  only allow NULL node revision ID in the `reset' case. */
            if((change.getNodeRevID() == null) && 
                    (FSPathChangeKind.FS_PATH_CHANGE_RESET.equals(change.getKind()) == false)){
                SVNErrorManager.error("Missing required node revision ID");
            }
            /* Sanity check: we should be talking about the same node
            revision ID as our last change except where the last change
            was a deletion*/
            if((change.getNodeRevID() != null) && 
                    (oldChange.getRevNodeId().equals(change.getNodeRevID()) == false) && 
                    (oldChange.getChangeKind().equals(FSPathChangeKind.FS_PATH_CHANGE_DELETE) == false)){
                SVNErrorManager.error("Invalid change ordering: new node revision ID without delete");
            }
            /* Sanity check: an add, replacement, or reset must be the first
            thing to follow a deletion*/            
            if(FSPathChangeKind.FS_PATH_CHANGE_DELETE.equals(oldChange.getChangeKind()) && 
                    false == ( FSPathChangeKind.FS_PATH_CHANGE_REPLACE.equals(change.getKind()) || 
                               FSPathChangeKind.FS_PATH_CHANGE_RESET.equals(change.getKind()) ||
                               FSPathChangeKind.FS_PATH_CHANGE_ADD.equals(change.getKind())) ){
                SVNErrorManager.error("Invalid change ordering: non-add change on deleted path");
            }    
            /*Merging the changes*/
            switch(change.getKind().intValue()){
                case 0 /*FSPathChangeKind.FS_PATH_CHANGE_MODIFY*/ :
                    if(change.getTextModi()){
                        oldChange.setTextModified(true);
                    }
                    if(change.getPropModi()){
                        oldChange.setPropertiesModified(true);
                    }
                    break;
                case 1 /*FSPathChangeKind.FS_PATH_CHANGE_ADD*/ :
                case 3 /*FSPathChangeKind.FS_PATH_CHANGE_REPLACE*/ :
                    /*An add at this point must be following a previous delete,
                    so treat it just like a replace*/        
                    oldChange.setChangeKind(FSPathChangeKind.FS_PATH_CHANGE_REPLACE);
                    oldChange.setRevNodeId(new FSID(change.getNodeRevID()));
                    oldChange.setTextModified(change.getTextModi());
                    oldChange.setPropertiesModified(change.getPropModi());
                    if(change.getCopyfromEntry().getRevision() == FSConstants.SVN_INVALID_REVNUM){
                        copyfromEntry = new SVNLocationEntry(FSConstants.SVN_INVALID_REVNUM, "");
                    }else{
                        copyfromEntry = new SVNLocationEntry(change.getCopyfromEntry().getRevision(), change.getCopyfromEntry().getPath());
                    }
                    break;
                case 2 /*FSPathChangeKind FS_PATH_CHANGE_DELETE*/:
                    if(FSPathChangeKind.FS_PATH_CHANGE_ADD.equals(oldChange.getChangeKind())){
                        /*If the path was introduced in this transaction via an
                        add, and we are deleting it, just remove the path altogether*/
                        oldChange = null;
                        internalMapChanges.remove(change.getPath());
                    }else{
                        /* A deletion overrules all previous changes. */
                        oldChange.setChangeKind(FSPathChangeKind.FS_PATH_CHANGE_DELETE);
                        oldChange.setPropertiesModified(change.getPropModi());
                        oldChange.setTextModified(change.getTextModi());
                    }
                    copyfromEntry = null;
                    internalMapCopyfrom.remove(change.getPath());
                    break;                    
                case 4 : /*FSPathChangeKind.FS_PATH_CHANGE_RESET*/
                    //A reset here will simply remove the path change from the hash
                    oldChange = null;
                    copyfromEntry = null;
                    internalMapChanges.remove(change.getPath());
                    internalMapCopyfrom.remove(change.getPath());
                    break;
            }
            newChange = oldChange;
        }else{
            newChange = new FSPathChange(new FSID(change.getNodeRevID()), change.getKind(), change.getTextModi(), change.getPropModi());
            if(change.getCopyfromEntry().getRevision() != FSConstants.SVN_INVALID_REVNUM){
                copyfromEntry = change.getCopyfromEntry();
            }else{
                copyfromEntry = new SVNLocationEntry(FSConstants.SVN_INVALID_REVNUM, "");
            }
            path = new String(change.getPath());
        }
        /*If passed value is null, we remove previous entry from hash (if it is there), otherwise nothing happend*/
        if(newChange == null){
            internalMapChanges.put(path, newChange);            
        }else{
            internalMapChanges.remove(path);
        }
        
        if(copyfromPath == null){
            copyfromPath = copyfromEntry != null ? new String(path) : path;
        }
        if(copyfromEntry == null){
            internalMapCopyfrom.remove(copyfromPath);
        }else{
            internalMapCopyfrom.put(copyfromPath, new SVNLocationEntry(copyfromEntry.getRevision(), copyfromEntry.getPath()));            
        }      
        
        ArrayList arr = new ArrayList(0);
        arr.add(internalMapChanges);
        arr.add(internalMapCopyfrom);
        return arr;
    }
    
    private class LogPathInfo{
    	private FSRevisionNode root;
    	private String path;
    	private FSNodeHistory hist;
    	private long historyRev;
    	
    	private LogPathInfo(FSRevisionNode newRoot, String newPath, FSNodeHistory newHist, long newHistoryRev){
    		root = newRoot;
    		path = newPath;
    		hist = newHist;
    		historyRev = newHistoryRev;
    	}
/*    	private void setRoot(FSRevisionNode newRoot){
    		root = newRoot;
    	}
    	private void setPath(String newPath){
    		path = newPath;    		
    	}
    	private void setHistory(FSNodeHistory newHist){
    		hist = newHist;
    	}
    	private void setRevision(long newRev){
    		historyRev = newRev;
    	}
*/  	private FSNodeHistory getHistory(){
    		return hist;
    	}
    	private long getHistoryRevision(){
    		return historyRev;
    	}
    	/*Set hist field of the class to next history for the path
    	 * If no more history is available or the history revision is less
    	 * than (earlier) than START, or the history is not available due
    	 * to authorization, then HIST is set to NULL.
    	 *
    	 * A STRICT value of FALSE will indicate to follow history across copied
    	 * paths.
    	 */
    	private void pickUpNextHistory(File reposRootDir, boolean strict, long start)throws SVNException{
    		FSNodeHistory tempHist = hist.fsHistoryPrev(reposRootDir, strict ? true : false, myRevNodesPool);
    		if(tempHist == null){
    			hist = null;
    			return;
    		}
   			hist = tempHist;
    		path = hist.getHistoryEntry().getPath();
    		historyRev = hist.getHistoryEntry().getRevision();
    		if(historyRev < start){
    			hist = null;
    			return;
    		}
    		return;
    	}
    	/* Set HIST to the next history for the path *if* there is history
    	 * available and HISTORY_REV is equal to or greater than CURRENT.
    	 */
    	private boolean checkHistory(File reposRootDir, long currRev, boolean strict, long start)throws SVNException{
    		if(hist == null){
    			return false;
    		}
    		if(historyRev < currRev){
    			return false;
    		}
    		this.pickUpNextHistory(reposRootDir, strict, start);
    		return true;
    	}
    }    

    public int getLocations(String pathCame, long pegRevision, long[] revisions, ISVNLocationEntryHandler handler) throws SVNException {
    	try{
    		openRepository();
    		String localPathCame = null;
    		if(pathCame == null){
    			pathCame = new String("");
    		}else{
    			localPathCame = new String(pathCame);
    		}    		
    		if(revisions == null){
    			revisions = new long[1];
    			revisions[0] = pegRevision;
    		}    	
    		if(handler == null){
    			SVNErrorManager.error("invalid ISVNLocationEntryHandler");
    		}
            ArrayList locationEntries = new ArrayList(0);            
            long[] locationRevs = new long[revisions.length];
            long revision;
            FSRevisionNode root = null;
            	
    		//String repositoryPath = super.getRepositoryPath(localPathCame);
            //String parentPath = SVNPathUtil.removeTail(repositoryPath);
            String parentPath = super.getRepositoryPath(localPathCame);
            
            //check if parentPath is really absolute path relatively to repository
            if(parentPath.charAt(0) != '/'){
            	parentPath = "/".concat(parentPath);
            }
            //Sort revisions from greatest downward
            Arrays.sort(revisions);
            for(int count = 0; count < revisions.length; ++count){
            	locationRevs[count] = revisions[revisions.length-(count+1)];
            }                        
           	//Ignore revisions R that are younger than the pegRevisions where
            //path@pegRevision is not an ancestor of path@R.
            int count = 0;
            for(count=0; count<locationRevs.length && locationRevs[count]>pegRevision; ++count){
            	if(true == FSNodeHistory.checkAncestryOfPegPath(myReposRootDir, parentPath, pegRevision, locationRevs[count], myRevNodesPool)){
            		break;
            	}
            }
            if(count >= locationRevs.length){
            	revision = pegRevision;
            }else{
            	revision = locationRevs[count];
            }    
            
            String path = parentPath;            	
            while(count < revisions.length){
            	FSRevisionNode croot = null;            	  		
            	long crev = FSConstants.SVN_INVALID_REVNUM;    		
            	//Find the target of the innermost copy relevant to path@revision.
           	    //The copy may be of path itself, or of a parent directory.            	
           		root = FSReader.getRootRevNode(myReposRootDir, revision);
           		FSClosestCopy tempClCopy = closestCopy(myReposRootDir, root, path);
           		if(tempClCopy == null){
           			break;
           		}
           		if( null == (croot = tempClCopy.getRevisionNode()) ){
           			break;
           		}
           		String cpath = tempClCopy.getPath();

           		//Assign the current path to all younger revisions until we reach
           		//the copy target rev
            	crev = croot.getId().isTxn() ? FSConstants.SVN_INVALID_REVNUM : croot.getId().getRevision();
            	while((count < revisions.length) && (locationRevs[count] >= crev)){
            		locationEntries.add(new SVNLocationEntry(locationRevs[count], path));
            		++count;
            	}
            	// Follow the copy to its source.  Ignore all revs between the
                // copy target rev and the copy source rev (non-inclusive).
            	SVNLocationEntry sEntry = copiedFrom(myReposRootDir, croot, cpath, myRevNodesPool);
           		while((count < revisions.length) && locationRevs[count] > sEntry.getRevision() ){
           			++count;
           		}
           	    /* Ultimately, it's not the path of the closest copy's source
                   that we care about -- it's our own path's location in the
                   copy source revision.  So we'll tack the relative path that
                   expresses the difference between the copy destination and our
                   path in the copy revision onto the copy source path to
                   determine this information.  

                   In other words, if our path is "/branches/my-branch/foo/bar",
                   and we know that the closest relevant copy was a copy of
                   "/trunk" to "/branches/my-branch", then that relative path
                   under the copy destination is "/foo/bar".  Tacking that onto
                   the copy source path tells us that our path was located at
                   "/trunk/foo/bar" before the copy.
                */
            	String reminder = (path.compareTo(cpath) == 0) ? new String("") : new String(SVNPathUtil.pathIsChild(cpath, path));
            	path = SVNPathUtil.concatToAbs(sEntry.getPath(), reminder);    		
            	revision = sEntry.getRevision();
            }
           	/* There are no copies relevant to path@revision.  So any remaining
               revisions either predate the creation of path@revision or have
               the node existing at the same path.  We will look up path@lrev
               for each remaining location-revision and make sure it is related
               to path@revision. */    	
       		root = FSReader.getRootRevNode(myReposRootDir, revision);
       		FSRevisionNode curNode = FSReader.getRevisionNode(myReposRootDir, path, root, 0);

       		while(count < revisions.length){
            	SVNNodeKind kind = null;    		

            	root = FSReader.getRootRevNode(myReposRootDir, revisions[count]);
            	//root = myRevNodesPool.getRootRevisionNode(revisions[count], myReposRootDir);            	
            	//kind = checkPath(myReposRootDir, root, path);            	
            	///!!!!!not completely clear what revision should be used
            	kind = this.checkNodeKind(path, new FSRoot(root.getId().getRevision(), root), root.getId().getRevision());
            	if(kind == SVNNodeKind.NONE){
            		break;
            	}
            	FSRevisionNode currentNode = FSReader.getRevisionNode(myReposRootDir, path, root, 0);            	
            	if( FSID.checkIdsRelated(curNode.getId(), currentNode.getId()) == false ){
            		break;
            	}
            	locationEntries.add(new SVNLocationEntry(locationRevs[count], path));
            	++count;
            }       		
            for(count = 0; count < locationEntries.size(); count++)
            {
            	handler.handleLocationEntry((SVNLocationEntry)locationEntries.get(count));
            }
            return count;
    	}finally{
    		closeRepository();
    	}        

    }

    public void diff(SVNURL url, long revision, String target, boolean ignoreAncestry, boolean recursive, ISVNReporterBaton reporter, ISVNEditor editor) throws SVNException {
        try {
            openRepository();
            File tmpFile = FSWriter.createUniqueTemporaryFile("report", ".tmp");
            makeReporterContext(revision, tmpFile, target, url, recursive, ignoreAncestry, true, editor);
            reporter.report(this);
        } finally {
            closeRepository();
        }
    }

    public void diff(SVNURL url, long targetRevision, long revision, String target, boolean ignoreAncestry, boolean recursive, ISVNReporterBaton reporter, ISVNEditor editor) throws SVNException {
        try {
            openRepository();
            File tmpFile = FSWriter.createUniqueTemporaryFile("report", ".tmp");
            makeReporterContext(targetRevision, tmpFile, target, url, recursive, ignoreAncestry, true, editor);
            reporter.report(this);
        } finally {
            closeRepository();
        }
    }

    public void update(long revision, String target, boolean recursive, ISVNReporterBaton reporter, ISVNEditor editor) throws SVNException {
        try {
            openRepository();
            File tmpFile = FSWriter.createUniqueTemporaryFile("report", ".tmp");
            makeReporterContext(revision, tmpFile, target, null, recursive, false, true, editor);
            reporter.report(this);
        } finally {
            closeRepository();
        }
    }

    public void status(long revision, String target, boolean recursive, ISVNReporterBaton reporter, ISVNEditor editor) throws SVNException {
        try {
            openRepository();
            File tmpFile = FSWriter.createUniqueTemporaryFile("report", ".tmp");
            makeReporterContext(revision, tmpFile, target, null, recursive, false, false, editor);
            reporter.report(this);
        } finally {
            closeRepository();
        }
    }
    
    private void makeReporterContext(long targetRevision, File reportFile, String target, SVNURL switchURL, boolean recursive, boolean ignoreAncestry, boolean textDeltas, ISVNEditor editor) throws SVNException{
        target = target == null ? "" : target;
        if (!isValidRevision(targetRevision)) {
            targetRevision = getYoungestRev(myReposRootDir);
        }
        /* If switchURL was provided, validate it and convert it into a
         * regular filesystem path. 
         */
        String switchPath = null;
        if(switchURL != null){
            /* Sanity check:  the switchURL better be in the same repository 
             * as the original session url! 
             */
            SVNURL reposRootURL = getRepositoryRoot();
            if(switchURL.toString().indexOf(reposRootURL.toString()) == -1){
                SVNErrorManager.error("'" + switchURL + "'" + SVNFileUtil.getNativeEOLMarker() + "is not the same repository as" + SVNFileUtil.getNativeEOLMarker() + "'" + getRepositoryRoot() + "'");
            }
            switchPath = switchURL.toString().substring(reposRootURL.toString().length());
        }
        String anchor = getRepositoryPath("");
        String fullTargetPath = switchPath != null ? switchPath : SVNPathUtil.concatToAbs(anchor, target);
        myReporterContext = new FSReporterContext(targetRevision, reportFile, target, fullTargetPath, switchURL == null ? false : true, recursive, ignoreAncestry, textDeltas, editor);
    }
    
    public void update(SVNURL url, long revision, String target, boolean recursive, ISVNReporterBaton reporter, ISVNEditor editor) throws SVNException {
        try {
            openRepository();
            File tmpFile = FSWriter.createUniqueTemporaryFile("report", ".tmp");
            makeReporterContext(revision, tmpFile, target, url, recursive, true, true, editor);
            reporter.report(this);
        } finally {
            closeRepository();
        }
    }

    public SVNDirEntry info(String path, long revision) throws SVNException {
        return null;
    }

    public ISVNEditor getCommitEditor(String logMessage, Map locks, boolean keepLocks, ISVNWorkspaceMediator mediator) throws SVNException {
        try {
            openRepository();
        } catch(SVNException svne) {
            closeRepository();
            throw svne;
        }
        //TODO: create and return an FSCommitEditor instance
        //TODO: to delete when finished!
        return null;
    }

    public SVNLock getLock(String path) throws SVNException {
        if(myReposRootDir == null || myReposRootDir.getAbsolutePath() == null){
            SVNErrorManager.error("Filesystem object was not created yet");
        }
        if(path == null){
            SVNErrorManager.error("Bad path for lock");
        }
        String reposPath = SVNPathUtil.canonicalizeAbsPath(path);
        SVNLock lock = FSReader.getLock(reposPath, false, null, myReposRootDir);        
        return lock;
    }    
    
    public SVNLock[] getLocks(String path) throws SVNException {
        if(myReposRootDir == null || myReposRootDir.getAbsolutePath() == null){
            SVNErrorManager.error("Filesystem object was not created yet");
        }
        if(path == null){
            SVNErrorManager.error("Bad path for locks");
        }
        String reposPath = SVNPathUtil.canonicalizeAbsPath(path);
        String digestPath = FSRepositoryUtil.getDigestFromRepositoryPath(reposPath);
        ArrayList locks = new ArrayList(0);
        locks = FSReader.walkDigestFiles(FSRepositoryUtil.getDigestFileFromDigest(digestPath, myReposRootDir), myReposRootDir, locks);
        if(locks == null || locks.isEmpty()){
            return null;
        }
        SVNLock [] retLocks = new SVNLock[locks.size()];
        for(int count = 0; count < locks.size(); count++){
            retLocks[count] = (SVNLock)locks.get(count);
        }
        return retLocks;
    }

    public void lock(Map pathsToRevisions, String comment, boolean force, ISVNLockHandler handler) throws SVNException {
        if(myReposRootDir == null || myReposRootDir.getAbsolutePath() == null){
            SVNErrorManager.error("File object was not created yet");
        }
        if(pathsToRevisions == null || pathsToRevisions.isEmpty()){
            return;
        }
        Set keyPathsSet = pathsToRevisions.keySet();
        Iterator keyIter = keyPathsSet.iterator();
        while(keyIter.hasNext()){
            String keyPath = (String)keyIter.next();
            /*make operation on every path to locked and invoke path to be locked*/
        }
    }

    public void unlock(Map pathToTokens, boolean force, ISVNLockHandler handler) throws SVNException {
    }

    public void closeSession() throws SVNException {
    }

    public void setPath(String path, String lockToken, long revision, boolean startEmpty) throws SVNException {
        assertValidRevision(revision);
        try {
            FSWriter.writePathInfoToReportFile(myReporterContext.getReportFileForWriting(), myReporterContext.getReportTarget(), path, null, lockToken, revision, startEmpty);
        } catch (IOException ioe) {
            SVNErrorManager.error("svn: Can't write path info: " + ioe.getMessage());
        }
    }

    public void deletePath(String path) throws SVNException {
        try {
            FSWriter.writePathInfoToReportFile(myReporterContext.getReportFileForWriting(), myReporterContext.getReportTarget(), path, null, null, FSConstants.SVN_INVALID_REVNUM, false);
        } catch (IOException ioe) {
            SVNErrorManager.error("svn: Can't write path info: " + ioe.getMessage());
        }
    }

    public void linkPath(SVNURL url, String path, String lockToken, long revision, boolean startEmpty) throws SVNException {
        assertValidRevision(revision);
        SVNURL reposRootURL = getRepositoryRoot();
        if(url.getPath().indexOf(reposRootURL.getPath()) == -1){
            SVNErrorManager.error("'" + url + "'" + SVNFileUtil.getNativeEOLMarker() + "is not the same repository as" + SVNFileUtil.getNativeEOLMarker() + "'" + reposRootURL + "'");
        }
        String reposLinkPath = url.toString().substring(reposRootURL.toString().length());
        try {
            FSWriter.writePathInfoToReportFile(myReporterContext.getReportFileForWriting(), myReporterContext.getReportTarget(), path, reposLinkPath, lockToken, revision, startEmpty);
        } catch (IOException ioe) {
            SVNErrorManager.error("svn: Can't write path info: " + ioe.getMessage());
        }
    }
   
    public void finishReport() throws SVNException {
        OutputStream tmpFile = myReporterContext.getReportFileForWriting();
        try {
            tmpFile.write('-');
        } catch (IOException ioe) {
            SVNErrorManager.error("svn: Can't finish report: " + ioe.getMessage());
        }
        SVNFileUtil.closeFile(myReporterContext.getReportFileForWriting());
        /*
         * Read the first pathinfo from the report and verify that it is a
         * top-level set_path entry.
         */

        PathInfo info = null;
        try {
            info = myReporterContext.getFirstPathInfo();
        } catch (IOException ioe) {
            SVNErrorManager.error("svn: Can't read report file: " + ioe.getMessage());
        }
        if (info == null || !info.getPath().equals(myReporterContext.getReportTarget()) || info.getLinkPath() != null || isInvalidRevision(info.getRevision())) {
            SVNErrorManager.error("svn: Invalid report for top level of working copy");
        }
        
        long sourceRevision = info.getRevision();
        
        /* Initialize the lookahead pathinfo. */
        PathInfo lookahead = null;
        try {
            lookahead = myReporterContext.getNextPathInfo();
        } catch (IOException ioe) {
            SVNErrorManager.error("svn: Can't read report file: " + ioe.getMessage());
        }
        
        if(lookahead != null && lookahead.getPath().equals(myReporterContext.getReportTarget())){
            if("".equals(myReporterContext.getReportTarget())){
                SVNErrorManager.error("svn: Two top-level reports with no target");
            }
            /* If the operand of the wc operation is switched or deleted,
             * then info above is just a place-holder, and the only thing we
             * have to do is pass the revision it contains to open_root.
             * The next pathinfo actually describes the target. 
             */
            info = lookahead;
            try{
                myReporterContext.getNextPathInfo();
            }catch(IOException ioe){
                SVNErrorManager.error("svn: Can't read report file: " + ioe.getMessage());
            }
        }
       
        myReporterContext.getEditor().targetRevision(myReporterContext.getTargetRevision());

        String fullTargetPath = myReporterContext.getReportTargetPath(); 
        String fullSourcePath = SVNPathUtil.concatToAbs(getRepositoryPath(""), myReporterContext.getReportTarget());
        FSEntry targetEntry = fakeDirEntry(fullTargetPath, myReporterContext.getTargetRoot(), myReporterContext.getTargetRevision());
        FSEntry sourceEntry = fakeDirEntry(fullSourcePath, null, sourceRevision);
        
        /* 
         * If the operand is a locally added file or directory, it won't
         * exist in the source, so accept that. 
         */
        if(isValidRevision(info.getRevision()) && info.getLinkPath() == null && sourceEntry == null){
            fullSourcePath = null;
        }
        
        /* If the anchor is the operand, the source and target must be dirs.
         * Check this before opening the root to avoid modifying the wc. 
         */
        if("".equals(myReporterContext.getReportTarget()) && (sourceEntry == null || sourceEntry.getType() != SVNNodeKind.DIR || targetEntry == null || targetEntry.getType() != SVNNodeKind.DIR)){
            SVNErrorManager.error("svn: Cannot replace a directory from within");
        }
        
        myReporterContext.getEditor().openRoot(sourceRevision);
        
        /* If the anchor is the operand, diff the two directories; otherwise
         * update the operand within the anchor directory. 
         */
        if("".equals(myReporterContext.getReportTarget())){
            diffDirs(sourceRevision, fullSourcePath, fullTargetPath, "", info.isStartEmpty());
        }else{
            //update entry
            updateEntry(sourceRevision, fullSourcePath, sourceEntry, fullTargetPath, targetEntry, myReporterContext.getReportTarget(), info, true);
        }

        myReporterContext.getEditor().closeDir();
        myReporterContext.getEditor().closeEdit();
        
        disposeReporterContext();
        
    }

    public void abortReport() throws SVNException {
        disposeReporterContext();
    }

    /* Emit edits within directory (with corresponding path editPath) with 
     * the changes from the directory sourceRevision/sourcePath to the
     * directory myReporterContext.getTargetRevision()/targetPath.  
     * sourcePath may be null if the entry does not exist in the source. 
     */
    private void diffDirs(long sourceRevision, String sourcePath, String targetPath, String editPath, boolean startEmpty) throws SVNException {
        /* Compare the property lists.  If we're starting empty, pass a null
         * source path so that we add all the properties. When we support 
         * directory locks, we must pass the lock token here. */
        diffProplists(sourceRevision, startEmpty == true ? null : sourcePath, editPath, targetPath, null, true);
        /* Get the list of entries in each of source and target. */
        Map sourceEntries = null;
        if(sourcePath != null && !startEmpty){
            sourceEntries = FSReader.getDirEntries(myRevNodesPool.getRevisionNode(sourceRevision, sourcePath, myReposRootDir), myReposRootDir);
        }
        Map targetEntries = FSReader.getDirEntries(myRevNodesPool.getRevisionNode(myReporterContext.getTargetRoot(), targetPath, myReposRootDir), myReposRootDir);
        /* Iterate over the report information for this directory. */
        while(true){
            Object[] nextInfo = fetchPathInfo(editPath);
            String entryName = (String)nextInfo[0];
            if(entryName == null){
                break;
            }
            PathInfo pathInfo = (PathInfo)nextInfo[1];
            if(pathInfo != null && isInvalidRevision(pathInfo.getRevision())){
                /* We want to perform deletes before non-replacement adds,
                 * for graceful handling of case-only renames on
                 * case-insensitive client filesystems.  So, if the report
                 * item is a delete, remove the entry from the source hash,
                 * but don't update the entry yet. 
                 */
                if(sourceEntries != null){
                    sourceEntries.remove(entryName);
                }
                continue;
            }
            
            String entryEditPath = SVNPathUtil.append(editPath, entryName);
            String entryTargetPath = SVNPathUtil.concatToAbs(targetPath, entryName);
            FSEntry targetEntry = (FSEntry)targetEntries.get(entryName);
            String entrySourcePath = sourcePath != null ? SVNPathUtil.concatToAbs(sourcePath, entryName) : null;
            FSEntry sourceEntry = sourceEntries != null ? (FSEntry)sourceEntries.get(entryName) : null;
            updateEntry(sourceRevision, entrySourcePath, sourceEntry, entryTargetPath, targetEntry, entryEditPath, pathInfo, myReporterContext.isRecursive());
            /* Don't revisit this entryName in the target or source entries. */
            targetEntries.remove(entryName);
            if(sourceEntries != null){
                sourceEntries.remove(entryName);
            }
        }
        
        /* Remove any deleted entries. Do this before processing the
         * target, for graceful handling of case-only renames. 
         */
        if(sourceEntries != null){
            Object[] names = sourceEntries.keySet().toArray();
            for(int i = 0; i < names.length; i++){
                FSEntry srcEntry = (FSEntry)sourceEntries.get(names[i]);
                if(targetEntries.get(srcEntry.getName()) == null){
                    /* There is no corresponding target entry, so delete. */
                    String entryEditPath = SVNPathUtil.append(editPath, srcEntry.getName());
                    if(myReporterContext.isRecursive() || srcEntry.getType() != SVNNodeKind.DIR){
                        myReporterContext.getEditor().deleteEntry(entryEditPath, FSConstants.SVN_INVALID_REVNUM);
                    }
                }
            }
        }
        /* Loop over the dirents in the target. */
        Object[] names = targetEntries.keySet().toArray();
        for(int i = 0; i < names.length; i++){
            FSEntry tgtEntry = (FSEntry)targetEntries.get(names[i]);
            /* Compose the report, editor, and target paths for this entry. */
            String entryEditPath = SVNPathUtil.append(editPath, tgtEntry.getName());
            String entryTargetPath = SVNPathUtil.concatToAbs(targetPath, tgtEntry.getName());
            /* Look for an entry with the same name in the source dirents. */
            FSEntry srcEntry = sourceEntries != null ? (FSEntry)sourceEntries.get(tgtEntry.getName()) : null;
            String entrySourcePath = srcEntry != null ? SVNPathUtil.concatToAbs(sourcePath, tgtEntry.getName()) : null;
            updateEntry(sourceRevision, entrySourcePath, srcEntry, entryTargetPath, tgtEntry, entryEditPath, null, myReporterContext.isRecursive());
        }        
    }

    /* Makes the appropriate edits to change file (represented by 
     * editPath) contents and properties from those in 
     * sourceRevision/sourcePath to those in 
     * myReporterContext.getTargetRevision()/targetPath,
     * possibly using lockToken to determine if the client's lock on 
     * the file is defunct. 
     */
    private void diffFiles(long sourceRevision, String sourcePath, String targetPath, String editPath, String lockToken) throws SVNException {
        /* Compare the files' property lists.  */
        diffProplists(sourceRevision, sourcePath, editPath, targetPath, lockToken, false);
        String sourceHexDigest = null;
        if(sourcePath != null){
            FSRevisionNode sourceRoot = myRevNodesPool.getRootRevisionNode(sourceRevision, myReposRootDir);//FSReader.getRootRevNode(myReposRootDir, sourceRevision);
            /* Is this delta calculation worth our time?  If we are ignoring
             * ancestry, then our editor implementor isn't concerned by the
             * theoretical differences between "has contents which have not
             * changed with respect to" and "has the same actual contents
             * as".  We'll do everything we can to avoid transmitting even
             * an empty text-delta in that case.  
             */
            boolean changed = false;
            if(myReporterContext.isIgnoreAncestry()){
                changed = checkFilesDifferent(sourceRoot, sourcePath, myReporterContext.getTargetRoot(), targetPath); 
            }else{
                changed = areFileContentsChanged(sourceRoot, sourcePath, myReporterContext.getTargetRoot(), targetPath);
            }
            if(!changed){
                return;
            }
            FSRevisionNode sourceNode = myRevNodesPool.getRevisionNode(sourceRoot, sourcePath, myReposRootDir);
            sourceHexDigest = FSRepositoryUtil.getFileChecksum(sourceNode);
        }
        /* Sends the delta stream if desired, or just calls 
         * the editor's textDeltaEnd() if not. 
         */
        myReporterContext.getEditor().applyTextDelta(editPath, sourceHexDigest);
        if(myReporterContext.isSendTextDeltas()){
            File srcFile = FSWriter.createUniqueTemporaryFile("source", ".tmp");
            File tgtFile = FSWriter.createUniqueTemporaryFile("target", ".tmp");
            OutputStream file1OS = SVNFileUtil.openFileForWriting(srcFile);
            OutputStream file2OS = SVNFileUtil.openFileForWriting(tgtFile);
            FSRevisionNode sourceNode = myRevNodesPool.getRevisionNode(sourceRevision, sourcePath, myReposRootDir);
            FSRevisionNode targetNode = myRevNodesPool.getRevisionNode(myReporterContext.getTargetRoot(), targetPath, myReposRootDir);
            FSReader.getFileContents(sourceNode, file1OS, myReposRootDir);
            FSReader.getFileContents(targetNode, file2OS, myReposRootDir);
            SVNFileUtil.closeFile(file1OS);
            SVNFileUtil.closeFile(file2OS);
            SVNRAFileData srcRAFile = new SVNRAFileData(srcFile, true);
            SVNRAFileData tgtRAFile = new SVNRAFileData(tgtFile, true);
            SVNSequenceDeltaGenerator generator = new SVNSequenceDeltaGenerator(FSWriter.getTmpDir());
            //TODO: replace this single window generation code with code that
            //generates windows of a fixed length
            generator.generateDiffWindow(editPath, myReporterContext.getEditor(), tgtRAFile, srcRAFile);
            srcFile.delete();
            tgtFile.delete();
        }else{
            myReporterContext.getEditor().textDeltaEnd(editPath);
        }
    }    
    
    /*
     * Returns true - if files are really different, their contents are
     * different. Otherwise return false - they are the same file 
     */
    private boolean checkFilesDifferent(FSRevisionNode root1, String path1, FSRevisionNode root2, String path2) throws SVNException {
        boolean changed = areFileContentsChanged(root1, path1, root2, path2);
        /* If the filesystem claims the things haven't changed, then 
         * they haven't changed. 
         */
        if(!changed){
            return false;
        }
        /* From this point on, assume things haven't changed. */
        /* So, things have changed.  But we need to know if the two sets 
         * of file contents are actually different. If they have differing
         * sizes, then we know they differ. 
         */
        FSRevisionNode revNode1 = myRevNodesPool.getRevisionNode(root1, path1, myReposRootDir);
        FSRevisionNode revNode2 = myRevNodesPool.getRevisionNode(root2, path2, myReposRootDir);
        if(getFileLength(revNode1) != getFileLength(revNode2)){
            return true;
        }
        /* Same sizes? Well, if their checksums differ, we know 
         * they differ. 
         */
        if(!FSRepositoryUtil.getFileChecksum(revNode1).equals(FSRepositoryUtil.getFileChecksum(revNode2))){
            return true;
        }
        /* Same sizes, same checksums. Chances are really good that 
         * files don't differ, but to be absolute sure, we need to 
         * compare bytes. 
         */
        File file1 = FSWriter.createUniqueTemporaryFile("source", ".tmp");
        File file2 = FSWriter.createUniqueTemporaryFile("target", ".tmp");
        OutputStream file1OS = SVNFileUtil.openFileForWriting(file1);
        OutputStream file2OS = SVNFileUtil.openFileForWriting(file2);
        FSReader.getFileContents(revNode1, file1OS, myReposRootDir);
        FSReader.getFileContents(revNode2, file2OS, myReposRootDir);
        SVNFileUtil.closeFile(file1OS);
        SVNFileUtil.closeFile(file2OS);
        InputStream file1IS = SVNFileUtil.openFileForReading(file1);
        InputStream file2IS = SVNFileUtil.openFileForReading(file2);
        int r1 = -1;
        int r2 = -1;
        while(true){
            try{
                r1 = file1IS.read();
                r2 = file2IS.read();
            }catch(IOException ioe){
                SVNFileUtil.closeFile(file1IS);
                SVNFileUtil.closeFile(file2IS);
                SVNErrorManager.error("svn: Can't read temporary file: "+ioe.getMessage());
            }
            if(r1 != r2){
                SVNFileUtil.closeFile(file1IS);
                SVNFileUtil.closeFile(file2IS);
                return true;
            }
            if(r1 == -1){
                break;
            }
        }
        return false;
    }
    
    private long getFileLength(FSRevisionNode revNode) throws SVNException {
        if(revNode.getType() != SVNNodeKind.FILE){
            SVNErrorManager.error("svn: Attempted to get length of a *non*-file node");
        }
        return revNode.getTextRepresentation() != null ? revNode.getTextRepresentation().getExpandedSize() : 0;
    }

    
    /*
     * Returns true if nodes' representations are different.
     */
    private boolean areFileContentsChanged(FSRevisionNode root1, String path1, FSRevisionNode root2, String path2) throws SVNException {
        /* Is there a need to check here that both roots 
         *  Check that both paths are files. 
         */
        if(checkNodeKind(path1, new FSRoot(root1.getId().getRevision(), root1), -1) != SVNNodeKind.FILE){
            SVNErrorManager.error("svn: '" + path1 + "' is not a file");
        }
        if(checkNodeKind(path2, new FSRoot(root2.getId().getRevision(), root2), -1) != SVNNodeKind.FILE){
            SVNErrorManager.error("svn: '" + path2 + "' is not a file");
        }
        FSRevisionNode revNode1 = myRevNodesPool.getRevisionNode(root1, path1, myReposRootDir);
        FSRevisionNode revNode2 = myRevNodesPool.getRevisionNode(root2, path2, myReposRootDir);
        return !FSRepositoryUtil.areContentsEqual(revNode1, revNode2);
    }
    
    /* Emits a series of editing operations to transform a source entry to
     * a target entry.
     * 
     * sourceRevision and sourcePath specify the source entry.  sourceEntry 
     * contains the already-looked-up information about the node-revision 
     * existing at that location. sourcePath and sourceEntry may be null if 
     * the entry does not exist in the source.  spurcePath may be non-null 
     * and sourceEntry may be null if the caller expects pathInfo to modify 
     * the source to an existing location.
     *
     * targetPath specify the target entry.  targetEntry contains
     * the already-looked-up information about the node-revision existing
     * at that location. targetPath and targetEntry may be null if the entry 
     * does not exist in the target.
     *
     * editPath should be passed to the editor calls as the pathname. 
     * editPath is the anchor-relative working copy pathname, which may 
     * differ from the source and target pathnames if the report contains a 
     * linkPath.
     *
     * pathInfo contains the report information for this working copy path, 
     * or null if there is none.  This method will internally modify the
     * source and target entries as appropriate based on the report
     * information.
     * 
     * If recursive is false, avoids operating on directories.  (Normally
     * recursive is simply taken from myReporterContext.isRecursive(), but 
     * finishReport() needs to force us to recurse into the target even if 
     * that flag is not set.) 
     */
    private void updateEntry(long sourceRevision, String sourcePath, FSEntry sourceEntry, String targetPath, FSEntry targetEntry, String editPath, PathInfo pathInfo, boolean recursive) throws SVNException {
        /* For non-switch operations, follow link path in the target. */
        if(pathInfo != null && pathInfo.getLinkPath() != null && !myReporterContext.isSwitch()){
            targetPath = pathInfo.getLinkPath();
            targetEntry = fakeDirEntry(targetPath, myReporterContext.getTargetRoot(), myReporterContext.getTargetRevision());
        }
        if(pathInfo != null && isInvalidRevision(pathInfo.getRevision())){
            /* Delete this entry in the source. */
            sourcePath = null;
            sourceEntry = null;
        }else if(pathInfo != null && sourcePath != null){
            /* Follow the rev and possibly path in this entry. */
            sourcePath = pathInfo.getLinkPath() != null ? pathInfo.getLinkPath() : sourcePath;
            sourceRevision = pathInfo.getRevision();
            sourceEntry = fakeDirEntry(sourcePath, null, sourceRevision);
        }
        /* Don't let the report carry us somewhere nonexistent. */
        if(sourcePath != null && sourceEntry == null){
            SVNErrorManager.error("svn: Working copy path '" + editPath + "' does not exist in repository");
        }
        if(!recursive && ((sourceEntry != null && sourceEntry.getType() == SVNNodeKind.DIR) || (targetEntry != null && targetEntry.getType() == SVNNodeKind.DIR))){
            skipPathInfo(editPath);
            return;
        }
        /* If the source and target both exist and are of the same kind,
         * then find out whether they're related.  If they're exactly the
         * same, then we don't have to do anything (unless the report has
         * changes to the source).  If we're ignoring ancestry, then any two
         * nodes of the same type are related enough for us. 
         */
        boolean related = false;
        if(sourceEntry != null && targetEntry != null && sourceEntry.getType() == targetEntry.getType()){
            int distance = FSID.compareIds(sourceEntry.getId(), targetEntry.getId());
            if(distance == 0 && !PathInfo.isRelevant(myReporterContext.getCurrentPathInfo(), editPath) && (pathInfo == null || (!pathInfo.isStartEmpty() && pathInfo.getLinkPath() == null))){
                return;
            }else if(distance != -1 || myReporterContext.isIgnoreAncestry()){
                related = true;
            }
        }
        /* If there's a source and it's not related to the target, nuke it. */
        if(sourceEntry != null && !related){
            myReporterContext.getEditor().deleteEntry(editPath, -1);
            sourcePath = null;
        }
        /* If there's no target, we have nothing more to do. */
        if(targetEntry == null){
            skipPathInfo(editPath);
            return;
        }
        if(targetEntry.getType() == SVNNodeKind.DIR){
            if(related){
                myReporterContext.getEditor().openDir(editPath, sourceRevision);
            }else{
                myReporterContext.getEditor().addDir(editPath, null, -1);
            }
            diffDirs(sourceRevision, sourcePath, targetPath, editPath, pathInfo != null ? pathInfo.isStartEmpty() : false);
            myReporterContext.getEditor().closeDir();
        }else{
            if(related){
                myReporterContext.getEditor().openFile(editPath, sourceRevision);
            }else{
                myReporterContext.getEditor().addFile(editPath, null, -1);
            }
            diffFiles(sourceRevision, sourcePath, targetPath, editPath, pathInfo != null ? pathInfo.getLockToken() : null);
            FSRevisionNode targetNode = myRevNodesPool.getRevisionNode(myReporterContext.getTargetRoot(), targetPath, myReposRootDir);
            String targetHexDigest = FSRepositoryUtil.getFileChecksum(targetNode);
            myReporterContext.getEditor().closeFile(editPath, targetHexDigest);
        }
    }
    
    private FSEntry fakeDirEntry(String reposPath, FSRevisionNode root, long revision) throws SVNException {
        FSRevisionNode node = root != null ? myRevNodesPool.getRevisionNode(root, reposPath, myReposRootDir) : myRevNodesPool.getRevisionNode(revision, reposPath, myReposRootDir);
        FSEntry dirEntry = null;
        if(node != null){
            dirEntry = new FSEntry(node.getId(), node.getType(), SVNPathUtil.tail(node.getCreatedPath()));
        }
        return dirEntry;
    }

    /* Skip all path info entries relevant to prefix.  Called when the
     * editor drive skips a directory. 
     */
    private void skipPathInfo(String prefix) throws SVNException {
        while(PathInfo.isRelevant(myReporterContext.getCurrentPathInfo(), prefix)){
            try{
                myReporterContext.getNextPathInfo();
            }catch(IOException ioe){
                SVNErrorManager.error("svn: Can't read report file: " + ioe.getMessage());
            }
        }

    }
    
    /* Fetch the next pathinfo from the report file for a descendent of
     * prefix.  If the next pathinfo is for an immediate child of prefix,
     * sets Object[0] to the path component of the report information and
     * Object[1] to the path information for that entry.  If the next pathinfo
     * is for a grandchild or other more remote descendent of prefix, sets
     * Object[0] to the immediate child corresponding to that descendent and
     * sets Object[1] to null.  If the next pathinfo is not for a descendent of
     * prefix, or if we reach the end of the report, sets both Object[0] and 
     * Object[1] to null.
     *
     * At all times, myReporterContext.getCurrentPathInfo() is presumed to be 
     * the next pathinfo not yet returned as an immediate child, or null if we 
     * have reached the end of the report. 
     */
    private Object[] fetchPathInfo(String prefix) throws SVNException{
        Object[] result = new Object[2];
        PathInfo pathInfo = myReporterContext.getCurrentPathInfo(); 
        if(!PathInfo.isRelevant(pathInfo, prefix)){
            /* No more entries relevant to prefix. */
            result[0] = null;
            result[1] = null;
        }else{
            /* Take a look at the prefix-relative part of the path. */
            String relPath = "".equals(prefix) ? pathInfo.getPath() : pathInfo.getPath().substring(prefix.length() + 1);
            if(relPath.indexOf('/') != -1){
                /* Return the immediate child part; do not advance. */
                result[0] = relPath.substring(0, relPath.indexOf('/'));
                result[1] = null;
            }else{
                /* This is an immediate child; return it and advance. */
                result[0] = relPath;
                result[1] = pathInfo;
                try{
                    myReporterContext.getNextPathInfo();
                }catch(IOException ioe){
                    SVNErrorManager.error("svn: Can't read report file: " + ioe.getMessage());
                }
            }
        }
        return result;
    }

    /* Generate the appropriate property editing calls to turn the
     * properties of sourceRevision/sourcePath into those of 
     * myReporterContext.getTargetRevision()/targetPath. If 
     * sourcePath is null, this is an add, so assume the target 
     * starts with no properties. 
     */
    private void diffProplists(long sourceRevision, String sourcePath, String editPath, String targetPath, String lockToken, boolean isDir) throws SVNException {
        FSRevisionNode targetNode = myRevNodesPool.getRevisionNode(myReporterContext.getTargetRoot(), targetPath, myReposRootDir);
        if(targetNode == null){
            SVNErrorManager.error("svn: File not found: revision " + myReporterContext.getTargetRevision() + ", path '" + targetPath + "'");
        }
        long createdRevision = targetNode != null ? targetNode.getId().getRevision() : -1;  
        //why are we checking the created revision fetched from the rev-file? may the file be malformed - is this the reason...
        if(isValidRevision(createdRevision)){
            Map entryProps = FSRepositoryUtil.getMetaProps(myReposRootDir, createdRevision, this);
            /* Transmit the committed-rev. */
            changeProperty(editPath, SVNProperty.COMMITTED_REVISION, (String)entryProps.get(SVNProperty.COMMITTED_REVISION), isDir);
            /* Transmit the committed-date. */
            String committedDate = (String)entryProps.get(SVNProperty.COMMITTED_DATE);
            if(committedDate != null || sourcePath != null){
                changeProperty(editPath, SVNProperty.COMMITTED_DATE, committedDate, isDir);
            }
            /* Transmit the last-author. */
            String lastAuthor = (String)entryProps.get(SVNProperty.LAST_AUTHOR);
            if(lastAuthor != null || sourcePath != null){
                changeProperty(editPath, SVNProperty.LAST_AUTHOR, lastAuthor, isDir);
            }
            /* Transmit the UUID. */
            String uuid = (String)entryProps.get(SVNProperty.UUID);
            if(uuid != null || sourcePath != null){
                changeProperty(editPath, SVNProperty.UUID, uuid, isDir);
            }
        }
        /* Update lock properties. */
        if(lockToken != null){
            SVNLock lock = FSReader.getLock(targetPath, false, null, myReposRootDir);
            /* Delete a defunct lock. */
            if(lock == null || !lockToken.equals(lock.getID())){
                changeProperty(editPath, SVNProperty.LOCK_TOKEN, null, isDir);
            }
        }
        Map sourceProps = null;
        if(sourcePath != null){
            FSRevisionNode sourceNode = myRevNodesPool.getRevisionNode(sourceRevision, sourcePath, myReposRootDir);
            if(sourceNode == null){
                SVNErrorManager.error("svn: File not found: revision " + sourceRevision + ", path '"
                        + sourcePath + "'");
            }
            boolean propsChanged = !FSRepositoryUtil.arePropertiesEqual(sourceNode, targetNode);
            if(!propsChanged){
                return;
            }
            /* If so, go ahead and get the source path's properties. */
            sourceProps = FSReader.getProperties(sourceNode, myReposRootDir);
        }else{
            sourceProps = new HashMap();
        }
        /* Get the target path's properties */
        Map targetProps = FSReader.getProperties(targetNode, myReposRootDir);
        /* Now transmit the differences. */
        Map propsDiffs = FSRepositoryUtil.getPropsDiffs(sourceProps, targetProps);
        Object[] names = propsDiffs.keySet().toArray();
        for(int i = 0; i < names.length; i++){
            String propName = (String)names[i];
            changeProperty(editPath, propName, (String)propsDiffs.get(propName), isDir);
        }
    }
    
    private void changeProperty(String path, String name, String value, boolean isDir) throws SVNException{
        if(isDir){
            myReporterContext.getEditor().changeDirProperty(name, value);
        }else{
            myReporterContext.getEditor().changeFileProperty(path, name, value);
        }
    }
    
    private void disposeReporterContext(){
        if(myReporterContext != null){
            myReporterContext.disposeContext();
            myReporterContext = null;
        }
    }
    
    public static boolean isInvalidRevision(long revision) {
        return SVNRepository.isInvalidRevision(revision);
    }    
    
    public static boolean isValidRevision(long revision) {
        return SVNRepository.isValidRevision(revision);
    }
    
    private class FSReporterContext {
        private File myReportFile;
        private String myTarget;
        private OutputStream myReportOS;
        private InputStream myReportIS;
        private ISVNEditor myEditor;
        private long myTargetRevision;
        private boolean isRecursive;
        private PathInfo myCurrentPathInfo;
        private boolean ignoreAncestry;
        private boolean sendTextDeltas;
        private String myTargetPath;
        private boolean isSwitch;
        private FSRevisionNode myTargetRoot;
        
        public FSReporterContext(long revision, File tmpFile, String target, String targetPath, boolean isSwitch, boolean recursive, boolean ignoreAncestry, boolean textDeltas, ISVNEditor editor) {
            myTargetRevision = revision;
            myReportFile = tmpFile;
            myTarget = target;
            myEditor = editor;
            isRecursive = recursive;
            this.ignoreAncestry = ignoreAncestry;
            sendTextDeltas = textDeltas;
            myTargetPath = targetPath;
            this.isSwitch = isSwitch;
        }

        public OutputStream getReportFileForWriting() throws SVNException {
            if (myReportOS == null) {
                myReportOS = SVNFileUtil.openFileForWriting(myReportFile);
            }
            return myReportOS;
        }

        public boolean isIgnoreAncestry(){
            return ignoreAncestry;
        }

        public boolean isSwitch(){
            return isSwitch;
        }

        public boolean isSendTextDeltas(){
            return sendTextDeltas;
        }
        
        public String getReportTarget() {
            return myTarget;
        }

        public String getReportTargetPath() {
            return myTargetPath;
        }

        public void disposeContext() {
            SVNFileUtil.closeFile(myReportOS);
            SVNFileUtil.closeFile(myReportIS);
        }

        public ISVNEditor getEditor() {
            return myEditor;
        }

        public boolean isRecursive() {
            return isRecursive;
        }

        public long getTargetRevision() {
            return myTargetRevision;
        }
        
        public PathInfo getFirstPathInfo() throws IOException, SVNException {
            SVNFileUtil.closeFile(myReportIS);
            myReportIS = SVNFileUtil.openFileForReading(myReportFile);
            myCurrentPathInfo = FSReader.readPathInfoFromReportFile(myReportIS);
            return myCurrentPathInfo;
        }
        
        public PathInfo getNextPathInfo() throws IOException {
            myCurrentPathInfo = FSReader.readPathInfoFromReportFile(myReportIS);
            return myCurrentPathInfo;
        }

        public PathInfo getCurrentPathInfo() {
            return myCurrentPathInfo;
        }
        
        public FSRevisionNode getTargetRoot() throws SVNException {
            if(myTargetRoot == null){
                myTargetRoot = myRevNodesPool.getRootRevisionNode(myTargetRevision, myReposRootDir); 
            }
            return myTargetRoot; 
        }
    }
    
    public static SVNNodeKind checkPath(File reposRootDir, FSRevisionNode root, String path)throws SVNException{
    	FSRevisionNode node = null;
    	try{
    		node = FSReader.getRevisionNode(reposRootDir, path, root, 0);
    	}catch(SVNException ex){
    		SVNErrorManager.error("Unable to get revision node");
    	}	
        if(node == null){
            return SVNNodeKind.NONE;
        }
    	return node.getType();
    }
    
    /* Discover the copy ancestry of PATH under ROOT.  Return a relevant
     * ancestor/revision combination in PATH(SVNLocationEntry) and REVISON(SVNLocationEntry)*/
    private static SVNLocationEntry copiedFrom(File reposRootDir, FSRevisionNode root, String path, FSRevisionNodePool revNodesPool)throws SVNException{
    	FSRevisionNode node = revNodesPool.getRevisionNode(new FSRoot(root.getId().getRevision(), root), path, reposRootDir);
    	return new SVNLocationEntry(node.getCopyFromRevision(), node.getCopyFromPath());
    }
    
    public FSClosestCopy closestCopy(File reposRootDir, FSRevisionNode root, String path)throws SVNException{
    	/* check coming path */    	
    	if(path == null){
    		return null;
    	}    	
        FSParentPath parentPath = myRevNodesPool.getParentPath(new FSRoot(root.getId().getRevision(), root), path, true, reposRootDir); //FSParentPath.openParentPath(reposRootDir, root, path, 0, null);
    	
       /* Find the youngest copyroot in the path of this node-rev, which
        * will indicate the target of the innermost copy affecting the node-rev*/    	    	
    	SVNLocationEntry copyDstEntry = FSNodeHistory.findYoungestCopyroot(reposRootDir, parentPath);
    	if(copyDstEntry == null || copyDstEntry.getRevision() == FSConstants.SVN_INVALID_REVNUM){
    		/*There are no copies affecting this node-rev or copyRoot wasn't find*/
    		return null;
    	}
    	
       /* It is possible that this node was created from scratch at some
        * revision between COPY_DST_REV and REV.  Make sure that PATH
        * exists as of COPY_DST_REV and is related to this node-rev */
        FSRevisionNode copyDstRoot = FSReader.getRootRevNode(reposRootDir, copyDstEntry.getRevision());
    	SVNNodeKind kind = checkPath(reposRootDir, copyDstRoot, path);
    	if(kind == SVNNodeKind.NONE){
    		//return new FSClosestCopy(null, null);
            return null;
    	}
   		FSRevisionNode curRev = FSReader.getRevisionNode(reposRootDir, path, copyDstRoot, 0);
        if(parentPath.getRevNode() == null || curRev.getId() == null){
            /*no speech about relation*/
            return null;
        }
        if(FSID.checkIdsRelated(parentPath.getRevNode().getId(), curRev.getId()) == false){
			return null;
		}    	
	  /* One final check must be done here.  If you copy a directory and
       * create a new entity somewhere beneath that directory in the same
       * txn, then we can't claim that the copy affected the new entity.
       * For example, if you do:
       * 
       *            copy dir1 dir2
       *            create dir2/new-thing
       *            commit
       *            
       * then dir2/new-thing was not affected by the copy of dir1 to dir2.
       * We detect this situation by asking if PATH@COPY_DST_REV's
       * created-rev is COPY_DST_REV, and that node-revision has no
       * predecessors, then there is no relevant closest copy*/
        long createdRev = parentPath.getRevNode().getId().getRevision();
    	
    	if(createdRev == copyDstEntry.getRevision()){
    		if(parentPath.getRevNode().getPredecessorId() == null){
    			return null;
    		}
    	}    	
    	return new FSClosestCopy(copyDstRoot, copyDstEntry.getPath());
    }
}
