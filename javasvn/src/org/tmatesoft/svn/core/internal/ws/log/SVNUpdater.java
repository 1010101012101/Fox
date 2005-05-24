/*
 * Created on 10.05.2005
 */
package org.tmatesoft.svn.core.internal.ws.log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.io.ISVNCredentialsProvider;
import org.tmatesoft.svn.core.io.SVNException;
import org.tmatesoft.svn.core.io.SVNNodeKind;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.io.SVNRepositoryLocation;
import org.tmatesoft.svn.util.PathUtil;


public class SVNUpdater extends SVNBasicClient {

    public SVNUpdater(final ISVNCredentialsProvider credentialsProvider, ISVNEventListener eventDispatcher) {
        super(new ISVNRepositoryFactory() {
            public SVNRepository createRepository(String url) throws SVNException {
                SVNRepository repos = SVNRepositoryFactory.create(SVNRepositoryLocation.parseURL(url));
                repos.setCredentialsProvider(credentialsProvider);
                return repos;
            }
        }, null, eventDispatcher);
    }

    public SVNUpdater(ISVNRepositoryFactory repositoryFactory, ISVNEventListener eventDispatcher) {
        super(repositoryFactory, null, eventDispatcher);
    }

    public SVNUpdater(ISVNRepositoryFactory repositoryFactory, SVNOptions options, ISVNEventListener eventDispatcher) {
        super(repositoryFactory, options, eventDispatcher);
    }
    
    public long doUpdate(File file, SVNRevision revision, boolean recursive) throws SVNException {        
        long revNumber = getRevisionNumber(file, revision);
        SVNWCAccess wcAccess = createWCAccess(file);
        final SVNReporter reporter = new SVNReporter(wcAccess, true);
        try {
            wcAccess.open(true, recursive);
            SVNUpdateEditor editor = new SVNUpdateEditor(wcAccess, null, recursive);
            SVNRepository repos = createRepository(wcAccess.getTargetEntryProperty(SVNProperty.URL));
            String target = "".equals(wcAccess.getTargetName()) ? null : wcAccess.getTargetName();
            repos.update(revNumber, target, recursive, reporter, editor);

            if (editor.getTargetRevision() >= 0) {
                dispatchEvent(SVNEvent.createUpdateCompletedEvent(wcAccess, editor.getTargetRevision()));
            }
            return editor.getTargetRevision();
        } finally {
            wcAccess.close(true, recursive);
        }
    }

    public long doSwitch(File file, String url, SVNRevision revision, boolean recursive) throws SVNException {
        url = validateURL(url);
        long revNumber = getRevisionNumber(file, revision);
        SVNWCAccess wcAccess = createWCAccess(file);
        final SVNReporter reporter = new SVNReporter(wcAccess, true);
        try {
            wcAccess.open(true, recursive);
            SVNUpdateEditor editor = new SVNUpdateEditor(wcAccess, url, recursive);
            SVNRepository repos = createRepository(wcAccess.getTargetEntryProperty(SVNProperty.URL));
            String target = "".equals(wcAccess.getTargetName()) ? null : wcAccess.getTargetName();
            repos.update(url, revNumber, target, recursive, reporter, editor);
            
            if (editor.getTargetRevision() >= 0) {
                dispatchEvent(SVNEvent.createUpdateCompletedEvent(wcAccess, editor.getTargetRevision()));
            }
            return editor.getTargetRevision();
        } finally {
            wcAccess.close(true, recursive);
        }
    }
    
    public long doCheckout(String url, File dstPath, SVNRevision pegRevision, SVNRevision revision, boolean recursive) throws SVNException {
        url = validateURL(url);
        if (dstPath == null) {
            dstPath = new File(".", PathUtil.tail(url));
        }
        if (!revision.isValid() && !pegRevision.isValid()) {
            pegRevision = SVNRevision.HEAD;
            revision = SVNRevision.HEAD;
        } else if (!revision.isValid()) {
            revision = pegRevision;
        } else if (!pegRevision.isValid()) {
            pegRevision = revision;
        }
        url = getURL(url, pegRevision, revision);
        SVNRepository repos = createRepository(url);
        long revNumber = getRevisionNumber(url, revision);
        SVNNodeKind targetNodeKind = repos.checkPath("", revNumber);
        String uuid = repos.getRepositoryUUID();
        if (targetNodeKind == SVNNodeKind.FILE) {
            SVNErrorManager.error(0, null);
        } else if (targetNodeKind == SVNNodeKind.NONE) {
            SVNErrorManager.error(0, null);
        }
        long result = -1;
        if (!dstPath.exists() || (dstPath.isDirectory() && !SVNWCAccess.isVersionedDirectory(dstPath))) {
            createVersionedDirectory(dstPath, url, uuid, revNumber);
            result = doUpdate(dstPath, revision, recursive);
        } else if (dstPath.isDirectory() && SVNWCAccess.isVersionedDirectory(dstPath)) {
            SVNWCAccess wcAccess = SVNWCAccess.create(dstPath);
            if (url.equals(wcAccess.getTargetEntryProperty(SVNProperty.URL))) {
                result = doUpdate(dstPath, revision, recursive);
            } else {
                SVNErrorManager.error(0, null);
            }
        } else {
            SVNErrorManager.error(0, null);
        }
        return result;
    }
    
    public void doCopy(String srcURL, File dstPath, SVNRevision revision) throws SVNException {
        srcURL = validateURL(srcURL);
        long revNumber = getRevisionNumber(srcURL, revision);

        SVNRepository repos = createRepository(srcURL);
        SVNNodeKind srcKind = repos.checkPath("", revision.getNumber());
        if (srcKind == SVNNodeKind.NONE) {
            if (revision == SVNRevision.HEAD) {
                SVNErrorManager.error(0, null);
            }
            SVNErrorManager.error(0, null);
        }
        String srcUUID = repos.getRepositoryUUID();
        if (dstPath.isDirectory()) {
            dstPath = new File(dstPath, PathUtil.decode(PathUtil.tail(srcURL)));
            if (dstPath.exists()) {
                SVNErrorManager.error(0, null);
            }
        } else if (dstPath.exists()) {
            SVNErrorManager.error(0, null);
        }
        
        boolean sameRepositories = false;
        SVNWCAccess wcAccess = createWCAccess(dstPath); 
        try {
            wcAccess.open(true, false);
            // check for missing entry.
            SVNEntry entry = wcAccess.getAnchor().getEntries().getEntry(wcAccess.getTargetName());
            if (entry != null) {
                SVNErrorManager.error(0, null);
            }
            String uuid = wcAccess.getTargetEntryProperty(SVNProperty.UUID);
            sameRepositories = uuid.equals(srcUUID);
            if (srcKind == SVNNodeKind.DIR) {
                String dstURL = wcAccess.getAnchor().getEntries().getPropertyValue("", SVNProperty.URL);
                dstURL = PathUtil.append(dstURL, PathUtil.encode(dstPath.getName()));
                createVersionedDirectory(dstPath, dstURL, uuid, revNumber);
                
                SVNWCAccess wcAccess2 = createWCAccess(dstPath, wcAccess.getTargetName());
                wcAccess2.open(true, true);
                try {
                    SVNReporter reporter = new SVNReporter(wcAccess2, true);
                    SVNUpdateEditor editor = new SVNUpdateEditor(wcAccess2, null, true);
                    repos.update(revNumber, null, true, reporter, editor);
                    dispatchEvent(SVNEvent.createUpdateCompletedEvent(wcAccess, editor.getTargetRevision()));
                    if (sameRepositories) {
                        addDir(wcAccess.getAnchor(), dstPath.getName(), srcURL, editor.getTargetRevision());
                        addDir(wcAccess2.getAnchor(), "", srcURL, editor.getTargetRevision());
                        // fire added event.
                        dispatchEvent(SVNEvent.createAddedEvent(wcAccess, wcAccess.getAnchor(), 
                                wcAccess.getAnchor().getEntries().getEntry(dstPath.getName())));
                    } else {
                        SVNErrorManager.error(0, null);
                    }
                } finally {
                    wcAccess2.close(true, true);
                }
            } else {
                Map properties = new HashMap();
                File tmpFile = null;
                OutputStream os = null;
                try {
                    File baseTmpFile = wcAccess.getAnchor().getBaseFile(dstPath.getName(), true);
                    tmpFile = SVNFileUtil.createUniqueFile(baseTmpFile.getParentFile(), dstPath.getName(), ".tmp");                    
                    os = new FileOutputStream(tmpFile);
                    long fileRevision = repos.getFile("", revNumber, properties, os);
                    os.close();
                    os = null;
                    SVNFileUtil.rename(tmpFile, baseTmpFile);
                } catch (IOException e) {
                    SVNErrorManager.error(0, e);
                } finally {
                    if (os != null) {
                        try {
                            os.close();
                        } catch (IOException e) {
                        }
                    }
                    if (tmpFile != null) {
                        tmpFile.delete();
                    }
                }
                addFile(wcAccess.getAnchor(), dstPath.getName(), properties, sameRepositories ? srcURL : null, revNumber);
                wcAccess.getAnchor().runLogs();
                // fire added event.
                dispatchEvent(SVNEvent.createAddedEvent(wcAccess, wcAccess.getAnchor(), 
                        wcAccess.getAnchor().getEntries().getEntry(dstPath.getName())));
            }
        } finally {
            if (wcAccess != null) {
                wcAccess.close(true, false);
            }
        }
    }

    private void createVersionedDirectory(File dstPath, String url, String uuid, long revNumber) throws SVNException {
        SVNDirectory.createVersionedDirectory(dstPath);
        SVNWCAccess wcAccess = SVNWCAccess.create(dstPath);
        SVNEntries entries = wcAccess.getAnchor().getEntries();
        SVNEntry entry = entries.getEntry("");
        if (entry == null) {
            entry = entries.addEntry("");
        }
        entry.setURL(url);
        entry.setUUID(uuid);
        entry.setKind(SVNNodeKind.DIR);
        entry.setRevision(revNumber);
        entry.setIncomplete(true);
        entries.save(true);
    }

    private void addDir(SVNDirectory dir, String name, String copyFromURL, long copyFromRev) throws SVNException {
        SVNEntry entry = dir.getEntries().getEntry(name);
        if (entry == null) {
            entry = dir.getEntries().addEntry(name);
        }
        entry.setKind(SVNNodeKind.DIR);
        if (copyFromURL != null) {
            entry.setCopyFromRevision(copyFromRev);
            entry.setCopyFromURL(copyFromURL);
            entry.setCopied(true);
        }
        entry.scheduleForAddition();
        if ("".equals(name) && copyFromURL != null) {
            updateCopiedDirectory(dir, name);
        }
        dir.getEntries().save(true);
    }
    
    private void updateCopiedDirectory(SVNDirectory dir, String name) throws SVNException {
        SVNEntries entries = dir.getEntries();
        SVNEntry entry = entries.getEntry(name);
        if (entry != null) {
            entry.setCopied(true);
            if (entry.isFile()) {
                dir.getWCProperties(name).delete();
            }
            if (!"".equals(name) && entry.isDirectory()) {
                SVNDirectory childDir = dir.getChildDirectory(name);
                updateCopiedDirectory(childDir, "");
            } else if ("".equals(name)) {
                dir.getWCProperties("").delete();                
                for (Iterator ents = entries.entries(); ents.hasNext();) {
                    SVNEntry childEntry = (SVNEntry) ents.next();
                    if ("".equals(childEntry.getName())) {
                        continue;
                    }
                    updateCopiedDirectory(dir, childEntry.getName());
                }
                entries.save(true);
            }
        }
    }

    private void addFile(SVNDirectory dir, String fileName, Map properties, String copyFromURL, long copyFromRev) throws SVNException {
        SVNLog log = dir.getLog(0);
        Map regularProps = new HashMap();
        Map entryProps = new HashMap();
        Map wcProps = new HashMap();
        for (Iterator names = properties.keySet().iterator(); names.hasNext();) {
            String propName = (String) names.next();
            String propValue = (String) properties.get(propName);
            if (propName.startsWith(SVNProperty.SVN_ENTRY_PREFIX)) {
                entryProps.put(SVNProperty.shortPropertyName(propName), propValue);
            } else if (propName.startsWith(SVNProperty.SVN_WC_PREFIX)) {
                wcProps.put(propName, propValue);
            } else {
                regularProps.put(propName, propValue);
            }
        }
        entryProps.put(SVNProperty.shortPropertyName(SVNProperty.KIND), SVNProperty.KIND_FILE);
        entryProps.put(SVNProperty.shortPropertyName(SVNProperty.REVISION), "0");
        entryProps.put(SVNProperty.shortPropertyName(SVNProperty.SCHEDULE), SVNProperty.SCHEDULE_ADD);
        if (copyFromURL != null) {
            entryProps.put(SVNProperty.shortPropertyName(SVNProperty.COPYFROM_REVISION), Long.toString(copyFromRev));
            entryProps.put(SVNProperty.shortPropertyName(SVNProperty.COPYFROM_URL), copyFromURL);
            entryProps.put(SVNProperty.shortPropertyName(SVNProperty.COPIED), Boolean.TRUE.toString());
        }
        
        log.logChangedEntryProperties(fileName, entryProps);
        log.logChangedWCProperties(fileName, wcProps);
        dir.mergeProperties(fileName, regularProps, null, log);

        Map command = new HashMap();
        command.put(SVNLog.NAME_ATTR, SVNFileUtil.getBasePath(dir.getBaseFile(fileName, true)));
        command.put(SVNLog.DEST_ATTR, fileName);
        log.addCommand(SVNLog.COPY_AND_TRANSLATE, command, false);
        command.clear();
        command.put(SVNLog.NAME_ATTR, SVNFileUtil.getBasePath(dir.getBaseFile(fileName, true)));
        command.put(SVNLog.DEST_ATTR, SVNFileUtil.getBasePath(dir.getBaseFile(fileName, false)));
        log.addCommand(SVNLog.MOVE, command, false);
        log.save();
    }
}
 