/*
 * ====================================================================
 * Copyright (c) 2004-2006 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://tmate.org/svn/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.wc;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.internal.util.SVNEncodingUtil;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.util.SVNURLUtil;
import org.tmatesoft.svn.core.internal.wc.ISVNCommitPathHandler;
import org.tmatesoft.svn.core.internal.wc.SVNAdminUtil;
import org.tmatesoft.svn.core.internal.wc.SVNCancellableOutputStream;
import org.tmatesoft.svn.core.internal.wc.SVNCommitMediator;
import org.tmatesoft.svn.core.internal.wc.SVNCommitUtil;
import org.tmatesoft.svn.core.internal.wc.SVNCommitter;
import org.tmatesoft.svn.core.internal.wc.SVNDirectory;
import org.tmatesoft.svn.core.internal.wc.SVNEntries;
import org.tmatesoft.svn.core.internal.wc.SVNEntry;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNEventFactory;
import org.tmatesoft.svn.core.internal.wc.SVNFileType;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.internal.wc.SVNLog;
import org.tmatesoft.svn.core.internal.wc.SVNPropertiesManager;
import org.tmatesoft.svn.core.internal.wc.SVNWCManager;
import org.tmatesoft.svn.core.internal.wc.admin.ISVNLog;
import org.tmatesoft.svn.core.internal.wc.admin.SVNAdminArea;
import org.tmatesoft.svn.core.internal.wc.admin.SVNEntry2;
import org.tmatesoft.svn.core.internal.wc.admin.SVNVersionedProperties;
import org.tmatesoft.svn.core.internal.wc.admin.SVNWCAccess2;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.util.SVNDebugLog;

/**
 * The <b>SVNCopyClient</b> provides methods to perform any kinds of copying and moving that SVN
 * supports - operating on both Working Copies (WC) and URLs.
 * 
 * <p>
 * Copy operations allow a user to copy versioned files and directories with all their 
 * previous history in several ways. 
 * 
 * <p>
 * Supported copy operations are:
 * <ul>
 * <li> Working Copy to Working Copy (WC-to-WC) copying - this operation copies the source
 * Working Copy item to the destination one and schedules the source copy for addition with history.
 * <li> Working Copy to URL (WC-to-URL) copying - this operation commits to the repository (exactly
 * to that repository location that is specified by URL) a copy of the Working Copy item.
 * <li> URL to Working Copy (URL-to-WC) copying - this operation will copy the source item from
 * the repository to the Working Copy item and schedule the source copy for addition with history.
 * <li> URL to URL (URL-to-URL) copying - this is a fully repository-side operation, it commits 
 * a copy of the source item to a specified repository location (within the same repository, of
 * course). 
 * </ul>
 * 
 * <p> 
 * Besides just copying <b>SVNCopyClient</b> also is able to move a versioned item - that is
 * first making a copy of the source item and then scheduling the source item for deletion 
 * when operating on a Working Copy, or right committing the deletion of the source item when 
 * operating immediately on the repository.
 * 
 * <p>
 * Supported move operations are:
 * <ul>
 * <li> Working Copy to Working Copy (WC-to-WC) moving - this operation copies the source
 * Working Copy item to the destination one and schedules the source item for deletion.
 * <li> URL to URL (URL-to-URL) moving - this is a fully repository-side operation, it commits 
 * a copy of the source item to a specified repository location and deletes the source item. 
 * </ul>
 * 
 * <p>
 * Overloaded <b>doCopy()</b> methods of <b>SVNCopyClient</b> are similar to
 * <code>'svn copy'</code> and <code>'svn move'</code> commands of the SVN command line client. 
 * 
 * @version 1.1
 * @author  TMate Software Ltd.
 * @see     <a target="_top" href="http://tmate.org/svn/kb/examples/">Examples</a>
 * 
 */
public class SVNCopyClient extends SVNBasicClient {

    private ISVNCommitHandler myCommitHandler;
    private ISVNCommitParameters myCommitParameters;
    /**
     * Constructs and initializes an <b>SVNCopyClient</b> object
     * with the specified run-time configuration and authentication 
     * drivers.
     * 
     * <p>
     * If <code>options</code> is <span class="javakeyword">null</span>,
     * then this <b>SVNCopyClient</b> will be using a default run-time
     * configuration driver  which takes client-side settings from the 
     * default SVN's run-time configuration area but is not able to
     * change those settings (read more on {@link ISVNOptions} and {@link SVNWCUtil}).  
     * 
     * <p>
     * If <code>authManager</code> is <span class="javakeyword">null</span>,
     * then this <b>SVNCopyClient</b> will be using a default authentication
     * and network layers driver (see {@link SVNWCUtil#createDefaultAuthenticationManager()})
     * which uses server-side settings and auth storage from the 
     * default SVN's run-time configuration area (or system properties
     * if that area is not found).
     * 
     * @param authManager an authentication and network layers driver
     * @param options     a run-time configuration options driver     
     */
    public SVNCopyClient(ISVNAuthenticationManager authManager, ISVNOptions options) {
        super(authManager, options);
    }

    protected SVNCopyClient(ISVNRepositoryPool repositoryPool, ISVNOptions options) {
        super(repositoryPool, options);
    }
    

    /**
     * Sets an implementation of <b>ISVNCommitHandler</b> to 
     * the commit handler that will be used during commit operations to handle 
     * commit log messages. The handler will receive a clien's log message and items 
     * (represented as <b>SVNCommitItem</b> objects) that will be 
     * committed. Depending on implementor's aims the initial log message can
     * be modified (or something else) and returned back. 
     * 
     * <p>
     * If using <b>SVNCopyClient</b> without specifying any
     * commit handler then a default one will be used - {@link DefaultSVNCommitHandler}.
     * 
     * @param handler               an implementor's handler that will be used to handle 
     *                              commit log messages
     * @see   #getCommitHandler()
     * @see   SVNCommitItem
     */
    public void setCommitHandler(ISVNCommitHandler handler) {
        myCommitHandler = handler;
    }

    /**
     * Returns the specified commit handler (if set) being in use or a default one 
     * (<b>DefaultSVNCommitHandler</b>) if no special 
     * implementations of <b>ISVNCommitHandler</b> were 
     * previousely provided.
     *   
     * @return  the commit handler being in use or a default one
     * @see     #setCommitHandler(ISVNCommitHandler)
     * @see     DefaultSVNCommitHandler 
     */
    public ISVNCommitHandler getCommitHandler() {
        if (myCommitHandler == null) {
            myCommitHandler = new DefaultSVNCommitHandler();
        }
        return myCommitHandler;
    }

    /**
     * Sets commit parameters to use.
     * 
     * <p>
     * When no parameters are set {@link DefaultSVNCommitParameters default} 
     * ones are used. 
     * 
     * @param parameters commit parameters
     * @see              #getCommitParameters()
     */
    public void setCommitParameters(ISVNCommitParameters parameters) {
        myCommitParameters = parameters;
    }

    /**
     * Returns commit parameters. 
     * 
     * <p>
     * If no user parameters were previously specified, once creates and 
     * returns {@link DefaultSVNCommitParameters default} ones. 
     * 
     * @return commit parameters
     * @see    #setCommitParameters(ISVNCommitParameters)
     */
    public ISVNCommitParameters getCommitParameters() {
        if (myCommitParameters == null) {
            myCommitParameters = new DefaultSVNCommitParameters();
        }
       return myCommitParameters;
    }
    
    /**
     * Copies/moves a source URL to a destination one immediately committing changes
     * to a repository. Equivalent to <code>doCopy(srcURL, srcRevision, dstURL, isMove, false, commitMessage)</code>.
     * 
     * @param  srcURL         a source repository location URL
     * @param  srcRevision    a revision of <code>srcURL</code>
     * @param  dstURL         a target URL where <code>srcURL</code> is to be
     *                        copied/moved
     * @param  isMove         <span class="javakeyword">true</span> to move the source
     *                        to the target (only URL-to-URL), 
     *                        <span class="javakeyword">false</span> to copy
     * @param  commitMessage  a commit log message
     * @return                information on the committed revision
     * @see                   #doCopy(SVNURL, SVNRevision, SVNURL, boolean, boolean, String)                       
     */
    public SVNCommitInfo doCopy(SVNURL srcURL, SVNRevision srcRevision, SVNURL dstURL, boolean isMove, String commitMessage) throws SVNException {
        return doCopy(srcURL, srcRevision, dstURL, isMove, false, commitMessage);
    }
    
    /**
     * Copies/moves a source URL to a destination one immediately committing changes
     * to a repository. 
     * 
     * <p>
     * If <code>dstURL</code> and <code>srcURL</code> are the same, 
     * <code>failWhenDstExists</code> is <span class="javakeyword">false</span> and 
     * <code>srcURL</code> is a directory then this directory will be copied into itself.
     * 
     * <p> 
     * If <code>dstURL</code> is a directory, <code>dstURL</code> and <code>srcURL</code> are not the same, 
     * <code>failWhenDstExists</code> is <span class="javakeyword">false</span>, <code>dstURL</code> 
     * has not the last path element entry of <code>srcURL</code> then that entry will be copied into 
     * <code>dstURL</code>. 
     * 
     * @param  srcURL            a source repository location URL
     * @param  srcRevision       a revision of <code>srcURL</code>
     * @param  dstURL            a target URL where <code>srcURL</code> is to be
     *                           copied/moved
     * @param  isMove            <span class="javakeyword">true</span> to move the source
     *                           to the target (only URL-to-URL), 
     *                           <span class="javakeyword">false</span> to copy
     * @param failWhenDstExists  <span class="javakeyword">true</span> to force a failure if 
     *                           the destination exists   
     * @param  commitMessage     a commit log message
     * @return                   information on the committed revision
     * @throws SVNException      if one of the following is true:
     *                           <ul>
     *                           <li><code>srcURL</code> and <code>dstURL</code> are not in the
     *                           same repository  
     *                           <li><code>srcURL</code> was not found in <code>srcRevision</code>
     *                           <li><code>dstURL</code> and <code>srcURL</code> are the same and 
     *                           <code>failWhenDstExists</code> is <span class="javakeyword">true</span>
     *                           <li><code>dstURL</code> already exists and <code>failWhenDstExists</code> 
     *                           is <span class="javakeyword">true</span>
     *                           <li><code>dstURL</code> already exists, <code>failWhenDstExists</code> 
     *                           is <span class="javakeyword">false</span>, but <code>dstURL</code> 
     *                           already contains the top path element name of <code>srcURL</code> 
     *                           <li><code>isMove = </code><span class="javakeyword">true</span> and 
     *                           <code>dstURL = srcURL</code>
     *                           </ul>
     */
    public SVNCommitInfo doCopy(SVNURL srcURL, SVNRevision srcRevision, SVNURL dstURL, boolean isMove, boolean failWhenDstExists, String commitMessage) throws SVNException {
        SVNURL topURL = SVNURLUtil.getCommonURLAncestor(srcURL, dstURL);
        if (topURL == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNSUPPORTED_FEATURE, "Source and dest appear not to be in the same repository (src: ''{0}''; dst: ''{1}'')", new Object[] {srcURL, dstURL});
            SVNErrorManager.error(err);
        }
        boolean isResurrect = false;
        
        if (dstURL.equals(srcURL)) {
            if (failWhenDstExists) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, "Destination URL could not be the same as source URL");
                SVNErrorManager.error(err);
            }
            topURL = srcURL.removePathTail();
            isResurrect = true;
        }

        SVNRepository repository = createRepository(topURL, true);
        if (!dstURL.equals(repository.getRepositoryRoot(true)) && srcURL.getPath().startsWith(dstURL.getPath() + "/")) {
            isResurrect = true;
            topURL = topURL.removePathTail();
            repository = createRepository(topURL, true);
        }
        
        String srcPath = srcURL.equals(topURL) ? "" : srcURL.toString().substring(topURL.toString().length() + 1);
        srcPath = SVNEncodingUtil.uriDecode(srcPath);
        String dstPath = dstURL.equals(topURL) ? "" : dstURL.toString().substring(topURL.toString().length() + 1);
        dstPath = SVNEncodingUtil.uriDecode(dstPath);
        
        if ("".equals(srcPath) && isMove) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNSUPPORTED_FEATURE, "Cannot move URL ''{0}'' into itself", srcURL);
            SVNErrorManager.error(err);
        }
        
        long srcRevNumber = getRevisionNumber(srcRevision, repository, null);
        long latestRevision = repository.getLatestRevision();
        
        if (srcRevNumber < 0) {
            srcRevNumber = latestRevision;
        }
        SVNNodeKind srcKind = repository.checkPath(srcPath, srcRevNumber);
        if (srcKind == SVNNodeKind.NONE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_FOUND, "Path ''{0}'' does not exist in revision {1}", new Object[] {srcURL, new Long(srcRevNumber)});
            SVNErrorManager.error(err);
        }
        SVNNodeKind dstKind = repository.checkPath(dstPath, latestRevision);
        if (dstKind == SVNNodeKind.DIR) {
            if (failWhenDstExists) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, "Path ''{0}'' already exists", dstPath);
                SVNErrorManager.error(err);
            }
            dstPath = SVNPathUtil.append(dstPath, SVNPathUtil.tail(srcURL.getPath()));
            if (repository.checkPath(dstPath, latestRevision) != SVNNodeKind.NONE) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, "Path ''{0}'' already exists", dstPath);
                SVNErrorManager.error(err);
            }
        } else if (dstKind == SVNNodeKind.FILE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, "Path ''{0}'' already exists", dstPath);
            SVNErrorManager.error(err);
        }
        Collection commitItems = new ArrayList(2);
        commitItems.add(new SVNCommitItem(null, dstURL, srcURL, srcKind, SVNRevision.create(srcRevNumber), 
                true, false, false, false, true, false));
        if (isMove) {
            commitItems.add(new SVNCommitItem(null, srcURL, null, srcKind, SVNRevision.create(srcRevNumber), 
                    false, true, false, false, false, false));
        }
        commitMessage = getCommitHandler().getCommitMessage(commitMessage, (SVNCommitItem[]) commitItems.toArray(new SVNCommitItem[commitItems.size()]));
        if (commitMessage == null) {
            return SVNCommitInfo.NULL;
        }

        commitMessage = SVNCommitClient.validateCommitMessage(commitMessage);
        ISVNEditor commitEditor = repository.getCommitEditor(commitMessage, null, false, null);
        ISVNCommitPathHandler committer = new CopyCommitPathHandler(srcPath, srcRevNumber, srcKind, dstPath, isMove, isResurrect);
        Collection paths = isMove ? Arrays.asList(new String[] { srcPath, dstPath }) : Collections.singletonList(dstPath);

        SVNCommitInfo result = null;
        try {
            SVNCommitUtil.driveCommitEditor(committer, paths, commitEditor, -1);
            result = commitEditor.closeEdit();
        } catch (SVNException e) {
            try {
                commitEditor.abortEdit();
            } catch (SVNException inner) {
                //
            }
            SVNErrorMessage nestedErr = e.getErrorMessage();
            SVNErrorMessage err = SVNErrorMessage.create(nestedErr.getErrorCode(), "Commit failed (details follow):");
            SVNErrorManager.error(err, e);
        }
        if (result != null && result.getNewRevision() >= 0) { 
            dispatchEvent(SVNEventFactory.createCommitCompletedEvent(null, result.getNewRevision()), ISVNEventHandler.UNKNOWN);
        }
        return result != null ? result : SVNCommitInfo.NULL;
    }
    
    /**
     * Copies a source Working Copy path (or its repository location URL) to a destination 
     * URL immediately committing changes to a repository.
     * 
     * <p>
     * Equivalent to <code>doCopy(srcPath, srcRevision, dstURL, false, commitMessage)</code>. 
     * 
     * @param  srcPath        a source Working Copy path
     * @param  srcRevision    a revision of <code>srcPath</code>
     * @param  dstURL         a target URL where <code>srcPath</code> is to be
     *                        copied
     * @param  commitMessage  a commit log message
     * @return                information on the committed revision
     * @throws SVNException   if one of the following is true:
     *                        <ul>
     *                        <li><code>srcPath</code> is not under version control
     *                        <li><code>srcPath</code> has no URL
     *                        <li>the repository location of <code>srcPath</code> was not 
     *                        found in <code>srcRevision</code>
     *                        <li><code>dstURL</code> already exists
     *                        </ul>
     * @see                   #doCopy(File, SVNRevision, SVNURL, boolean, String)
     */
    public SVNCommitInfo doCopy(File srcPath, SVNRevision srcRevision, SVNURL dstURL, String commitMessage) throws SVNException {
        return doCopy(srcPath, srcRevision, dstURL, false, commitMessage);
    }
    
    /**
     * Copies a source Working Copy path (or its repository location URL) to a destination 
     * URL immediately committing changes to a repository.
     * 
     * <p>
     * If <code>srcRevision</code> is not {@link SVNRevision#WORKING} then the repository
     * location URL of <code>srcPath</code> is copied to <code>dstURL</code>. Otherwise
     * <code>srcPath</code> itself.
     * 
     * <p>
     * <code>failWhenDstExists</code> behaves 
     * like in {@link #doCopy(SVNURL, SVNRevision, SVNURL, boolean, boolean, String)}. 
     * 
     * @param  srcPath          a source Working Copy path
     * @param  srcRevision      a revision of <code>srcPath</code>
     * @param  dstURL           a target URL where <code>srcPath</code> is to be
     *                          copied
     * @param failWhenDstExists <span class="javakeyword">true</span> to force a failure if 
     *                          the destination exists   
     * @param  commitMessage    a commit log message
     * @return                  information on the committed revision
     * @throws SVNException     if one of the following is true:
     *                          <ul>
     *                          <li><code>srcPath</code> is not under version control
     *                          <li><code>srcPath</code> has no URL
     *                          <li>the repository location of <code>srcPath</code> was not 
     *                          found in <code>srcRevision</code>
     *                          <li><code>dstURL</code> already exists and 
     *                          <code>failWhenDstExists</code> is <span class="javakeyword">true</span>
     *                          </ul>
     */
    public SVNCommitInfo doCopy(File srcPath, SVNRevision srcRevision, SVNURL dstURL, boolean failWhenDstExists, String commitMessage) throws SVNException {
        // may be url->url.
        if (srcRevision.isValid() && srcRevision != SVNRevision.WORKING) {
            SVNWCAccess2 wcAccess = createWCAccess();
            wcAccess.probeOpen(srcPath, false, 0); 
            SVNEntry2 srcEntry = wcAccess.getEntry(srcPath, false);
            wcAccess.close();
            
            if (srcEntry == null) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNVERSIONED_RESOURCE, "''{0}'' is not under version control", srcPath);
                SVNErrorManager.error(err);
            }
            if (srcEntry.getURL() == null) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_MISSING_URL, "''{0}'' does not seem to have a URL associated with it", srcPath);
                SVNErrorManager.error(err);
            }
            return doCopy(srcEntry.getSVNURL(), srcRevision, dstURL, false, failWhenDstExists, commitMessage);
        }
        SVNWCAccess2 wcAccess = createWCAccess();
		SVNAdminArea adminArea = wcAccess.probeOpen(srcPath, false, SVNWCAccess2.INFINITE_DEPTH);
        
        SVNURL dstAnchorURL = dstURL.removePathTail();
        String dstTarget = SVNPathUtil.tail(dstURL.toString());
        dstTarget = SVNEncodingUtil.uriDecode(dstTarget);
        
        SVNRepository repository = createRepository(dstAnchorURL, true);
        SVNNodeKind dstKind = repository.checkPath(dstTarget, -1);
        if (dstKind == SVNNodeKind.DIR) {
            if (failWhenDstExists) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, "Path ''{0}'' already exists", dstURL);
                SVNErrorManager.error(err);
            }
            dstURL = dstURL.appendPath(srcPath.getName(), false);
        } else if (dstKind == SVNNodeKind.FILE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, "File ''{0}'' already exists", dstURL);
            SVNErrorManager.error(err);
        }

        SVNCommitItem[] items = new SVNCommitItem[] { 
                new SVNCommitItem(null, dstURL, null, SVNNodeKind.NONE, SVNRevision.UNDEFINED, true, false, false, false, true, false) 
                };
        commitMessage = getCommitHandler().getCommitMessage(commitMessage, items);
        if (commitMessage == null) {
            return SVNCommitInfo.NULL;
        }

        SVNAdminArea dirArea = null;
        if (SVNFileType.getType(srcPath) == SVNFileType.DIRECTORY) {
            dirArea = wcAccess.retrieve(srcPath);
        } else {
            dirArea = adminArea;
        }
        
        Collection tmpFiles = null;
        SVNCommitInfo info = null;
        ISVNEditor commitEditor = null;
        try {
            Map commitables = new TreeMap();
            SVNEntry2 entry = wcAccess.getEntry(srcPath, false);
            if (entry == null) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, "''{0}'' is not under version control", srcPath);
                SVNErrorManager.error(err);
                return SVNCommitInfo.NULL;
            }
            
            SVNCommitUtil.harvestCommitables(commitables, dirArea, srcPath, null, entry, dstURL.toString(), entry.getURL(), 
                    true, false, false, null, true, false, getCommitParameters());
            items = (SVNCommitItem[]) commitables.values().toArray(new SVNCommitItem[commitables.values().size()]);
            for (int i = 0; i < items.length; i++) {
                items[i].setAnchorArea(dirArea);
            }
            
            commitables = new TreeMap();
            dstURL = SVNURL.parseURIEncoded(SVNCommitUtil.translateCommitables(items, commitables));

            repository = createRepository(dstURL, true);
            SVNCommitMediator mediator = new SVNCommitMediator(commitables);
            tmpFiles = mediator.getTmpFiles();

            commitMessage = SVNCommitClient.validateCommitMessage(commitMessage);
            commitEditor = repository.getCommitEditor(commitMessage, null, false, mediator);
            info = SVNCommitter.commit(tmpFiles, commitables, repository.getRepositoryRoot(true).getPath(), commitEditor);
            commitEditor = null;
        } finally {
            if (tmpFiles != null) {
                for (Iterator files = tmpFiles.iterator(); files.hasNext();) {
                    File file = (File) files.next();
                    file.delete();
                }
            }
            if (commitEditor != null && info == null) {
                commitEditor.abortEdit();
            }
            if (wcAccess != null) {
                wcAccess.close();
            }
        }
        if (info != null && info.getNewRevision() >= 0) { 
            dispatchEvent(SVNEventFactory.createCommitCompletedEvent(null, info.getNewRevision()), ISVNEventHandler.UNKNOWN);
        }
        return info != null ? info : SVNCommitInfo.NULL;
    }
    
    /**
     * Copies a source URL to a destination Working Copy path. 
     * 
     * <p>
     * <code>dstPath</code> will be automatically scheduled for addition with history.
     * 
     * @param  srcURL         a source URL
     * @param  srcRevision    a revision of <code>srcURL</code>
     * @param  dstPath        a destination WC path
     * @return                the revision number of a source
     * @throws SVNException   if one of the following is true:
     *                        <ul>
     *                        <li><code>srcURL</code> was not found in <code>srcRevision</code>
     *                        <li><code>dstPath</code> already exists
     *                        <li><code>dstPath</code> appears in <code>srcURL</code>
     *                        <li><code>dstPath</code> and <code>srcURL</code> are from
     *                        different repositories
     *                        <li><code>dstPath</code> is under version control but missing
     *                        </ul>
     */
    public long doCopy(SVNURL srcURL, SVNRevision srcRevision, File dstPath) throws SVNException {
        SVNRepository repository = createRepository(srcURL, true);
        if (!srcRevision.isValid()) {
            srcRevision = SVNRevision.HEAD;
        }
        long srcRevisionNumber = getRevisionNumber(srcRevision, repository, null);
        SVNNodeKind srcKind = repository.checkPath("", srcRevisionNumber);
        
        if (srcKind == SVNNodeKind.NONE) {
            if (SVNRevision.isValidRevisionNumber(srcRevisionNumber)) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_FOUND, "Path ''{0}'' not found in revision {1}",
                        new Object[] {srcURL, new Long(srcRevisionNumber)});
                SVNErrorManager.error(err);
            } else {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_FOUND, "Path ''{0}'' not found in head revision", srcURL);
                SVNErrorManager.error(err);
            }
        }
        
        SVNFileType dstFileType = SVNFileType.getType(dstPath);
        if (dstFileType == SVNFileType.DIRECTORY) {
            dstPath = new File(dstPath, SVNPathUtil.tail(srcURL.getPath()));
        } else if (dstFileType != SVNFileType.NONE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_EXISTS, "File ''{0}'' already exists", dstPath);
            SVNErrorManager.error(err);
        }
        dstFileType = SVNFileType.getType(dstPath);
        if (dstFileType != SVNFileType.NONE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "''{0}'' is in the way", dstPath);
            SVNErrorManager.error(err);
        }
        
        SVNWCAccess2 dstAccess = createWCAccess();
        SVNAdminArea adminArea = dstAccess.probeOpen(dstPath, true, 0);
        
        SVNEntry2 dstEntry = dstAccess.getEntry(dstPath, false);
        if (dstEntry != null && !dstEntry.isDirectory() && !dstEntry.isScheduledForDeletion()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Entry for ''{0}'' exists (though the working copy file is missing)", dstPath);
            SVNErrorManager.error(err);
        }
        
        boolean sameRepositories;
        
        String srcUUID = null;
        String dstUUID = null;
        
        try {
            srcUUID = repository.getRepositoryUUID(true);
            dstUUID = getUUIDFromPath(dstAccess, dstPath.getParentFile());
        } catch (SVNException e) {
            if (e.getErrorMessage().getErrorCode() == SVNErrorCode.RA_NO_REPOS_UUID) {
                srcUUID = dstUUID = null;
            } else {
                throw e;
            }
        }
        if (dstUUID == null || srcUUID == null) {
            sameRepositories = false;
        } else {
            sameRepositories = srcUUID.equals(dstUUID);
        }
        
        long revision = -1;
        if (srcKind == SVNNodeKind.DIR) {
            // do checkout.
            SVNUpdateClient updateClient = new SVNUpdateClient(getRepositoryPool(), getOptions());
            updateClient.setEventHandler(getEventDispatcher());

            updateClient.setEventPathPrefix("");
            try {
                revision = updateClient.doCheckout(srcURL, dstPath, srcRevision, srcRevision, true);
            } finally {
                updateClient.setEventPathPrefix(null);
            }
            
            if (srcRevision == SVNRevision.HEAD && sameRepositories) {
                SVNAdminArea dstArea = dstAccess.open(dstPath, true, SVNWCAccess2.INFINITE_DEPTH);
                SVNEntry2 dstRootEntry = dstArea.getEntry(dstArea.getThisDirName(), false);
                revision = dstRootEntry.getRevision();
            }
            if (sameRepositories) {
                SVNWCManager.add(dstPath, adminArea, srcURL, revision);
            } else {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNSUPPORTED_FEATURE, "Source URL ''{0}'' is from foreign repository; leaving it as a disjoint WC", srcURL);
                SVNErrorManager.error(err);
            }
        } else if (srcKind == SVNNodeKind.FILE) {
            Map properties = new HashMap();
            File tmpFile = null;

            File baseTmpFile = adminArea.getBaseFile(dstPath.getName(), true);
            tmpFile = SVNFileUtil.createUniqueFile(baseTmpFile.getParentFile(), dstPath.getName(), ".tmp");
            OutputStream os = null;
            
            long realRevision = -1;
            try {
                os = SVNFileUtil.openFileForWriting(tmpFile);
                realRevision = repository.getFile("", srcRevisionNumber, properties, new SVNCancellableOutputStream(os, this));
            } finally {
                SVNFileUtil.closeFile(os);
            }
            if (!SVNRevision.isValidRevisionNumber(srcRevisionNumber)) {
                srcRevisionNumber = realRevision;
            }
            
            addRepositoryFile(adminArea, dstPath.getName(), null, tmpFile, null, properties,  
                    sameRepositories ? srcURL.toString() : null, 
                    sameRepositories ? srcRevisionNumber : -1);
            
            dispatchEvent(SVNEventFactory.createAddedEvent(null, adminArea, dstAccess.getEntry(dstPath, false)));
            revision = srcRevisionNumber;
        }
        dstAccess.close();
        return revision;
    }
    
    private String getUUIDFromPath (SVNWCAccess2 wcAccess, File path) throws SVNException {
        SVNEntry2 entry = wcAccess.getEntry(path, true);
        if (entry == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, "Can''t find entry for ''{0}''", path);
            SVNErrorManager.error(err);
        }
        
        String uuid = null;
        if (entry.getUUID() != null) {
            uuid = entry.getUUID();
        } else if (entry.getURL() != null) {
            SVNRepository repos = createRepository(entry.getSVNURL(), false);
            uuid = repos.getRepositoryUUID(true);
        } else {
            if (wcAccess.isWCRoot(path)) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_MISSING_URL, "''{0}'' has no URL", path);
                SVNErrorManager.error(err);
            }
            uuid = getUUIDFromPath(wcAccess, path.getParentFile());
        }
        return uuid;
    }
    
    /**
     * Copies/moves a source Working Copy path to a destination Working Copy path.
     * 
     * <p>
     * If <code>srcRevision</code> is not {@link SVNRevision#WORKING} and 
     * <code>isMove = </code><span class="javakeyword">false</span>, then the repository
     * location URL of <code>srcPath</code> is copied to <code>dstPath</code>. Otherwise
     * <code>srcPath</code> itself.
     * 
     * <p>
     * <code>dstPath</code> will be automatically scheduled for addition with history.
     * 
     * @param  srcPath        a source WC path
     * @param  srcRevision    a revision of <code>srcPath</code>
     * @param  dstPath        a destination WC path 
     * @param  force          <span class="javakeyword">true</span> to force the operation
     *                        to run
     * @param  isMove         <span class="javakeyword">true</span> to move the source
     *                        to the target (only WC-to-WC), 
     *                        <span class="javakeyword">false</span> to copy
     * @throws SVNException   if one of the following is true:
     *                        <ul>
     *                        <li><code>dstPath</code> already exists and is in the way
     *                        containing an item with the same name as the source
     *                        <li><code>srcPath</code> is not under version control
     *                        <li><code>srcPath</code> does not exist
     *                        <li><code>srcPath</code> has no URL
     *                        <li><code>dstPath</code> is a child of <code>srcPath</code>
     *                        <li><code>dstPath</code> is scheduled for deletion
     *                        <li><code>isMove = </code><span class="javakeyword">true</span> and 
     *                        <code>dstURL = srcURL</code>
     *                        </ul>
     */
    public void doCopy(File srcPath, SVNRevision srcRevision, File dstPath, boolean force, boolean isMove) throws SVNException {
        if (srcRevision.isValid() && srcRevision != SVNRevision.WORKING && !isMove) {
            // url->wc copy
            SVNWCAccess2 wcAccess = createWCAccess();
            SVNEntry2 srcEntry = wcAccess.getEntry(srcPath, false);
            if (srcEntry == null) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, "''{0}'' is not under version control", srcPath);
                SVNErrorManager.error(err);
            }
            if (srcEntry.getURL() == null) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_MISSING_URL, "''{0}'' has no URL", srcPath);
                SVNErrorManager.error(err);
            }
            doCopy(srcEntry.getSVNURL(), srcRevision, dstPath);
            return;
        }
        // 1. can't copy src to its own child
        if (SVNPathUtil.isChildOf(srcPath, dstPath)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNSUPPORTED_FEATURE, "Cannot copy ''{0}'' into its own child ''{1}''",
                    new Object[] {srcPath, dstPath});
            SVNErrorManager.error(err);
        }
        // 2. can't move path into itself
        if (isMove && srcPath.equals(dstPath)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNSUPPORTED_FEATURE, "Cannot move ''{0}'' into itself", srcPath);
            SVNErrorManager.error(err);
        }
        // 3. src should exist
        SVNFileType srcType = SVNFileType.getType(srcPath);
        if (srcType == SVNFileType.NONE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.NODE_UNKNOWN_KIND, "Path ''{0}'' does not exist", srcPath);
            SVNErrorManager.error(err);
        }
        // 4. if dst exists - use its child
        SVNFileType dstType = SVNFileType.getType(dstPath);
        if (dstType == SVNFileType.DIRECTORY) {
            dstPath = new File(dstPath, srcPath.getName());
            dstType = SVNFileType.getType(dstPath);
            if (dstType != SVNFileType.NONE) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "''{0}'' already exists and is in the way", dstPath);
                SVNErrorManager.error(err);
            }
        } else if (dstType != SVNFileType.NONE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_EXISTS, "File ''{0}'' already exists", dstPath);
            SVNErrorManager.error(err);
        }
        
        SVNWCAccess2 wcAccess = createWCAccess();
        SVNAdminArea adminArea = null;
        File srcParent = srcPath.getParentFile();
        File dstParent = dstPath.getParentFile();

        try {
            SVNAdminArea srcParentArea = null;
            if (isMove) {
                srcParentArea = wcAccess.open(srcParent, true, srcType == SVNFileType.DIRECTORY ? SVNWCAccess2.INFINITE_DEPTH : 0);
                if (srcParent.equals(dstParent)) {
                    adminArea = srcParentArea;
                } else {
                    if (srcType == SVNFileType.DIRECTORY && SVNPathUtil.isChildOf(srcParent, dstParent)) {
                        adminArea = wcAccess.retrieve(dstParent);
                    } else {
                        adminArea = wcAccess.open(dstParent, true, 0);
                    }
                }
                
                if (!force) {
                    try {
                        SVNWCManager.canDelete(srcPath, false, getOptions());
                    } catch (SVNException svne) {
                        SVNErrorMessage err = svne.getErrorMessage().wrap("Move will not be attempted unless forced");
                        SVNErrorManager.error(err, svne);
                    }
                }
            } else {
                adminArea = wcAccess.open(dstParent, true, 0);
            }
            
            SVNWCAccess2 copyAccess = SVNWCAccess2.newInstance(getEventDispatcher());
            try {
                SVNAdminArea srcArea = copyAccess.probeOpen(srcPath, false, SVNWCAccess2.INFINITE_DEPTH);
                SVNEntry2 dstEntry = adminArea.getEntry(adminArea.getThisDirName(), false);
                if (dstEntry == null) {
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, "''{0}'' is not under version control", adminArea.getRoot());
                    SVNErrorManager.error(err);
                }
                
                SVNEntry2 srcEntry = copyAccess.getEntry(srcPath, false);
                if (srcEntry == null) {
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, "''{0}'' is not under version control", srcPath);
                    SVNErrorManager.error(err);
                }
                
                if (srcEntry.getRepositoryRoot() != null && dstEntry.getRepositoryRoot() != null &&
                        !srcEntry.getRepositoryRoot().equals(dstEntry.getRepositoryRoot())) {
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_INVALID_SCHEDULE, "Cannot copy to ''{0}'', as it is not from repository ''{1}''; it is from ''{2}''", new Object[] {adminArea.getRoot(), srcEntry.getRepositoryRoot(), dstEntry.getRepositoryRoot()});
                    SVNErrorManager.error(err);
                }
                
                if (dstEntry.isScheduledForDeletion()) {
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_INVALID_SCHEDULE, "Cannot copy to ''{0}'' as it is scheduled for deletion", adminArea.getRoot());
                    SVNErrorManager.error(err);
                }
                
                if (srcType == SVNFileType.FILE) {
                    copyFile(adminArea, srcArea, srcPath, dstPath.getName());
                } else if (srcType == SVNFileType.DIRECTORY) {
                    copyDir(adminArea, srcArea, srcPath, dstPath.getName());
                }
            } finally {
                copyAccess.close();
            }
            if (isMove) {
                SVNWCManager.delete(srcParentArea.getWCAccess(), srcParentArea, srcPath, true);
            }
        } finally {
            wcAccess.close();
        }
        
    }


    private void addRepositoryFile(SVNAdminArea dir, String fileName, File text, File textBase, Map baseProperties, Map properties, String copyFromURL, long copyFromRev) throws SVNException {
        SVNEntry2 parentEntry = dir.getEntry(dir.getThisDirName(), false);
        if (parentEntry == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNVERSIONED_RESOURCE, "''{0}'' is not under version control", dir.getRoot());
            SVNErrorManager.error(err);
        }
        String newURL = SVNPathUtil.append(parentEntry.getURL(), SVNEncodingUtil.uriEncode(fileName));
        if (copyFromURL != null && parentEntry.getRepositoryRoot() != null && !SVNPathUtil.isAncestor(parentEntry.getRepositoryRoot(), copyFromURL)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNSUPPORTED_FEATURE, "Copyfrom-url ''{0}'' has different repository root than ''{1}''", new Object[]{copyFromURL, parentEntry.getRepositoryRoot()});
            SVNErrorManager.error(err);
        }

        SVNEntry2 dstEntry = dir.getEntry(fileName, false);
        ISVNLog log = dir.getLog();
        Map command = new HashMap();
        if (dstEntry != null && dstEntry.isScheduledForDeletion()) {
            String revertTextPath = SVNAdminUtil.getTextRevertPath(fileName, false);
            String baseTextPath = SVNAdminUtil.getTextBasePath(fileName, false);
            String revertPropsPath = SVNAdminUtil.getPropRevertPath(fileName, SVNNodeKind.FILE, false);
            String basePropsPath = SVNAdminUtil.getPropBasePath(fileName, SVNNodeKind.FILE, false);

            command.put(ISVNLog.NAME_ATTR, baseTextPath);
            command.put(ISVNLog.DEST_ATTR, revertTextPath);
            log.addCommand(SVNLog.MOVE, command, false);
            command.clear();
            
            if (dir.getFile(basePropsPath).isFile()) {
                command.put(ISVNLog.NAME_ATTR, basePropsPath);
                command.put(ISVNLog.DEST_ATTR, revertPropsPath);
                log.addCommand(SVNLog.MOVE, command, false);
                command.clear();
            }
        }
        
        Map entryAttrs = new HashMap();
        entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.SCHEDULE), SVNProperty.SCHEDULE_ADD);
        if (copyFromURL != null) {
            entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.COPIED), SVNProperty.toString(true));
            entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.COPYFROM_URL), copyFromURL);
            entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.COPYFROM_REVISION), SVNProperty.toString(copyFromRev));
        }
        log.logChangedEntryProperties(fileName, entryAttrs);
        entryAttrs.clear();
        
        entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.KIND), SVNProperty.KIND_FILE);
        entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.REVISION), SVNProperty.toString(dstEntry != null ? dstEntry.getRevision() : parentEntry.getRevision()));
        entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.URL), newURL);
        entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.ABSENT), null);
        entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.DELETED), null);
        log.logChangedEntryProperties(fileName, entryAttrs);
        entryAttrs.clear();

        addProperties(dir, fileName, baseProperties, true, log);
        addProperties(dir, fileName, properties, false, log);
        
        File tmpTextBase = dir.getBaseFile(fileName, true);
        if (!tmpTextBase.equals(textBase)) {
            SVNFileUtil.rename(textBase, tmpTextBase);
        }
        if (text != null) {
            File tmpFile = SVNFileUtil.createUniqueFile(dir.getRoot(), fileName, ".tmp");
            SVNFileUtil.rename(text, tmpFile);
            if (baseProperties.containsKey(SVNProperty.SPECIAL)) {
                command.put(ISVNLog.NAME_ATTR, tmpFile.getName());
                command.put(ISVNLog.DEST_ATTR, fileName);
                command.put(ISVNLog.ATTR1, "true");
                log.addCommand(ISVNLog.COPY, command, false);
                command.clear();
                command.put(ISVNLog.NAME_ATTR, tmpFile.getName());
                log.addCommand(ISVNLog.DELETE, command, false);
                command.clear();
            } else {
                command.put(ISVNLog.NAME_ATTR, tmpFile.getName());
                command.put(ISVNLog.DEST_ATTR, fileName);
                log.addCommand(ISVNLog.MOVE, command, false);
                command.clear();
            }
        } else {
            command.put(ISVNLog.NAME_ATTR, SVNAdminUtil.getTextBasePath(fileName, true));
            command.put(ISVNLog.DEST_ATTR, fileName);
            log.addCommand(ISVNLog.COPY_AND_TRANSLATE, command, false);
            command.clear();
            command.put(SVNProperty.shortPropertyName(SVNProperty.TEXT_TIME), ISVNLog.WC_TIMESTAMP);
            log.logChangedEntryProperties(fileName, command);
            command.clear();
        }
        


        command.put(ISVNLog.NAME_ATTR, SVNAdminUtil.getTextBasePath(fileName, true));
        command.put(ISVNLog.DEST_ATTR, SVNAdminUtil.getTextBasePath(fileName, false));
        log.addCommand(ISVNLog.MOVE, command, false);
        command.clear();
        
        command.put(ISVNLog.NAME_ATTR, SVNAdminUtil.getTextBasePath(fileName, false));
        log.addCommand(ISVNLog.READONLY, command, false);
        command.clear();

        String checksum = SVNFileUtil.computeChecksum(dir.getBaseFile(fileName, true));
        entryAttrs.put(SVNProperty.shortPropertyName(SVNProperty.CHECKSUM), checksum);
        log.logChangedEntryProperties(fileName, entryAttrs);
        entryAttrs.clear();
        
        log.save();
        dir.runLogs();
    }

    private void addProperties(SVNAdminArea dir, String fileName, Map properties, boolean base, ISVNLog log) throws SVNException {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        Map regularProps = new HashMap();
        Map entryProps = new HashMap();
        Map wcProps = new HashMap();

        for (Iterator names = properties.keySet().iterator(); names.hasNext();) {
            String propName = (String) names.next();
            String propValue = (String) properties.get(propName);
            if (SVNProperty.isEntryProperty(propName)) {
                entryProps.put(SVNProperty.shortPropertyName(propName), propValue);
            } else if (SVNProperty.isWorkingCopyProperty(propName)) {
                wcProps.put(propName, propValue);
            } else {
                regularProps.put(propName, propValue);
            }
        }
        SVNVersionedProperties props = base ? dir.getBaseProperties(fileName) : dir.getProperties(fileName);
        props.removeAll();
        for (Iterator propNames = regularProps.keySet().iterator(); propNames.hasNext();) {
            String propName = (String) propNames.next();
            String propValue = (String) regularProps.get(propName);
            props.setPropertyValue(propName, propValue);
        }
        dir.saveVersionedProperties(log, false);
        log.logChangedEntryProperties(fileName, entryProps);
        log.logChangedWCProperties(fileName, wcProps);
    }

    private void copyFile(SVNAdminArea dstParent, SVNAdminArea srcArea,  File srcPath, String dstName) throws SVNException {
        SVNWCAccess2 wcAccess = dstParent.getWCAccess();
        File dstPath = dstParent.getFile(dstName);
        
        if (SVNFileType.getType(dstPath) != SVNFileType.NONE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_EXISTS, "''{0}'' already exists and is in the way", dstPath);
            SVNErrorManager.error(err);
        }
        
        SVNEntry2 dstEntry = wcAccess.getEntry(dstPath, false);
        if (dstEntry != null && dstEntry.isFile()) {
            if (!dstEntry.isScheduledForDeletion()) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_EXISTS, "There is already a versioned item ''{0}''", dstPath);
                SVNErrorManager.error(err);
            }
        }
        SVNEntry2 srcEntry = srcArea.getWCAccess().getEntry(srcPath, false);
        if (srcEntry == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNVERSIONED_RESOURCE, "Cannot copy or move ''{0}'': it''s not under version control", srcPath);
            SVNErrorManager.error(err);
        }
        if (srcEntry.isScheduledForAddition() || srcEntry.getURL() == null || srcEntry.isCopied()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNSUPPORTED_FEATURE, "Cannot copy or move ''{0}'': it''s not in repository yet; try committing first", srcPath);
            SVNErrorManager.error(err);
        }
        File textBase = srcArea.getBaseFile(srcPath.getName(), false);
        File tmpTextBase = dstParent.getBaseFile(dstName, true);
        String copyFromURL = srcEntry.getURL();
        long copyFromRevision = srcEntry.getRevision();
        
        Map baseProperties = srcArea.getBaseProperties(srcEntry.getName()).asMap();
        Map properties = srcArea.getProperties(srcEntry.getName()).asMap();
        SVNDebugLog.getDefaultLog().info("base props of " + srcEntry.getName() + " : " + baseProperties);
        SVNDebugLog.getDefaultLog().info("props of " + srcEntry.getName() + " : " + baseProperties);
        
        SVNFileUtil.copyFile(textBase, tmpTextBase, false);
        File tmpFile = SVNFileUtil.createUniqueFile(dstParent.getRoot(), dstName, ".tmp");
        SVNFileUtil.copy(srcArea.getFile(srcEntry.getName()), tmpFile, false, false);
       
        addRepositoryFile(dstParent, dstName, tmpFile, tmpTextBase, baseProperties, properties, copyFromURL, copyFromRevision);
    
        SVNEvent event = SVNEventFactory.createAddedEvent(dstParent, dstName, SVNNodeKind.FILE, null);
        dstParent.getWCAccess().handleEvent(event);

    }

    private void copyDir(SVNAdminArea dstParent, SVNAdminArea srcArea, File srcPath, String dstName) throws SVNException {
        SVNEntry2 srcEntry = srcArea.getWCAccess().getEntry(srcPath, false);
        if (srcEntry == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, "'{0}'' is not under version control", srcPath);
            SVNErrorManager.error(err);
        }
        if (srcEntry.isScheduledForAddition() || srcEntry.getURL() == null || srcEntry.isCopied()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNSUPPORTED_FEATURE, "Cannot copy or move ''{0}'': it''s not in repository yet; try committing first", srcPath);
            SVNErrorManager.error(err);
        }
        
        File dstPath = dstParent.getFile(dstName);
        SVNFileUtil.copyDirectory(srcPath, dstPath, true, srcArea.getWCAccess());
        SVNWCClient wcClient = new SVNWCClient((ISVNAuthenticationManager) null, (ISVNOptions) null);
        wcClient.doCleanup(dstPath);
        
        SVNWCAccess2 tmpAccess = SVNWCAccess2.newInstance(null);
        try {
            SVNAdminArea tmpDir = tmpAccess.open(dstPath, true, SVNWCAccess2.INFINITE_DEPTH);
            postCopyCleanup(tmpDir);
        } finally {
            tmpAccess.close();
        }
        SVNWCManager.add(dstPath, dstParent, srcEntry.getSVNURL(), srcEntry.getRevision());
    }

    static void updateCopiedDirectory(SVNDirectory dir, String name, String newURL, String reposRootURL, String copyFromURL, long copyFromRevision) throws SVNException {
        SVNEntries entries = dir.getEntries();
        SVNEntry entry = entries.getEntry(name, true);
        if (entry != null) {
            entry.setCopied(true);
            if (newURL != null) {
                entry.setURL(newURL);
            }
            entry.setRepositoryRoot(reposRootURL);
            if (entry.isFile()) {
                dir.getWCProperties(name).delete();
                if (copyFromURL != null) {
                    entry.setCopyFromURL(copyFromURL);
                    entry.setCopyFromRevision(copyFromRevision);
                }
            }
            boolean deleted = false;
            if (entry.isDeleted() && newURL != null) {
                // convert to scheduled for deletion.
                deleted = true;
                entry.setDeleted(false);
                entry.scheduleForDeletion();
                if (entry.isDirectory()) {
                    entry.setKind(SVNNodeKind.FILE);
                }
            }
            if (entry.getLockToken() != null && newURL != null) {
                entry.setLockToken(null);
                entry.setLockOwner(null);
                entry.setLockComment(null);
                entry.setLockCreationDate(null);
            }
            if (!"".equals(name) && entry.isDirectory() && !deleted) {
                SVNDirectory childDir = dir.getChildDirectory(name);
                if (childDir != null) {
                    String childCopyFromURL = copyFromURL == null ? null : SVNPathUtil.append(copyFromURL, SVNEncodingUtil.uriEncode(entry.getName()));
                    updateCopiedDirectory(childDir, "", newURL, reposRootURL, childCopyFromURL, copyFromRevision);
                }
            } else if ("".equals(name)) {
                dir.getWCProperties("").delete();
                if (copyFromURL != null) {
                    entry.setCopyFromURL(copyFromURL);
                    entry.setCopyFromRevision(copyFromRevision);
                }
                for (Iterator ents = entries.entries(true); ents.hasNext();) {
                    SVNEntry childEntry = (SVNEntry) ents.next();
                    if ("".equals(childEntry.getName())) {
                        continue;
                    }
                    String childCopyFromURL = copyFromURL == null ? null : SVNPathUtil.append(copyFromURL, SVNEncodingUtil.uriEncode(childEntry.getName()));
                    String newChildURL = newURL == null ? null : SVNPathUtil.append(newURL, SVNEncodingUtil.uriEncode(childEntry.getName()));
                    updateCopiedDirectory(dir, childEntry.getName(), newChildURL, reposRootURL, childCopyFromURL, copyFromRevision);
                }
                entries.save(true);
            }
        }
    }

    static void postCopyCleanup(SVNAdminArea dir) throws SVNException {
        SVNPropertiesManager.deleteWCProperties(dir, null, false);
        SVNFileUtil.setHidden(dir.getAdminDirectory(), true);
        
        for(Iterator entries = dir.entries(true); entries.hasNext();) {
            SVNEntry2 entry = (SVNEntry2) entries.next();
            boolean deleted = entry.isDeleted();
            SVNNodeKind kind = entry.getKind();
            
            if (entry.isDeleted()) {
                entry.setSchedule(SVNProperty.SCHEDULE_DELETE);
                entry.setDeleted(false);
                if (entry.isDirectory()) {
                    entry.setKind(SVNNodeKind.FILE);
                }
            }
            if (entry.getLockToken() != null) {
                entry.setLockToken(null);
                entry.setLockOwner(null);
                entry.setLockCreationDate(null);
            }
            if (!deleted && kind == SVNNodeKind.DIR && !dir.getThisDirName().equals(entry.getName())) {
                SVNAdminArea childDir = dir.getWCAccess().retrieve(dir.getFile(entry.getName()));
                postCopyCleanup(childDir);
            }
        }
        dir.saveEntries(false);
    }

    private static class CopyCommitPathHandler implements ISVNCommitPathHandler {
        
        private String mySrcPath;
        private String myDstPath;
        private long mySrcRev;
        private boolean myIsMove;
        private boolean myIsResurrect;
        private SVNNodeKind mySrcKind;

        public CopyCommitPathHandler(String srcPath, long srcRev, SVNNodeKind srcKind, String dstPath, boolean isMove, boolean isRessurect) {
            mySrcPath = srcPath;
            myDstPath = dstPath;
            mySrcRev = srcRev;
            myIsMove = isMove;
            mySrcKind = srcKind;
            myIsResurrect = isRessurect;
        }
        
        public boolean handleCommitPath(String commitPath, ISVNEditor commitEditor) throws SVNException {
            boolean doAdd = false;
            boolean doDelete = false;
            if (myIsResurrect) {
                if (!myIsMove) {
                    doAdd = true;
                }
            } else {
                if (myIsMove) {
                    if (commitPath.equals(mySrcPath)) {
                        doDelete = true;
                    } else {
                        doAdd = true;
                    }
                } else {
                    doAdd = true;
                }
            }
            if (doDelete) {
                commitEditor.deleteEntry(mySrcPath, -1);
            }
            boolean closeDir = false;
            if (doAdd) {
                if (mySrcKind == SVNNodeKind.DIR) {
                    commitEditor.addDir(myDstPath, mySrcPath, mySrcRev);
                    closeDir = true;
                } else {
                    commitEditor.addFile(myDstPath, mySrcPath, mySrcRev);
                    commitEditor.closeFile(myDstPath, null);
                }
            }
            return closeDir;
        }
    }
}
