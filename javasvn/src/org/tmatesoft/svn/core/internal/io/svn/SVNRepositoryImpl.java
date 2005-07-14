/*
 * ====================================================================
 * Copyright (c) 2004 TMate Software Ltd. All rights reserved.
 * 
 * This software is licensed as described in the file COPYING, which you should
 * have received as part of this distribution. The terms are also available at
 * http://tmate.org/svn/license.html. If newer versions of this license are
 * posted there, you may use a newer version instead, at your option.
 * ====================================================================
 */

package org.tmatesoft.svn.core.internal.io.svn;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.internal.diff.SVNDiffWindow;
import org.tmatesoft.svn.core.internal.diff.SVNDiffWindowBuilder;
import org.tmatesoft.svn.core.io.ISVNCredentials;
import org.tmatesoft.svn.core.io.ISVNDirEntryHandler;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.ISVNFileRevisionHandler;
import org.tmatesoft.svn.core.io.ISVNLocationEntryHandler;
import org.tmatesoft.svn.core.io.ISVNLogEntryHandler;
import org.tmatesoft.svn.core.io.ISVNReporter;
import org.tmatesoft.svn.core.io.ISVNReporterBaton;
import org.tmatesoft.svn.core.io.ISVNWorkspaceMediator;
import org.tmatesoft.svn.core.io.SVNDirEntry;
import org.tmatesoft.svn.core.io.SVNException;
import org.tmatesoft.svn.core.io.SVNFileRevision;
import org.tmatesoft.svn.core.io.SVNLocationEntry;
import org.tmatesoft.svn.core.io.SVNLock;
import org.tmatesoft.svn.core.io.SVNLogEntry;
import org.tmatesoft.svn.core.io.SVNLogEntryPath;
import org.tmatesoft.svn.core.io.SVNNodeKind;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryLocation;
import org.tmatesoft.svn.util.DebugLog;
import org.tmatesoft.svn.util.PathUtil;

/**
 * @version 1.0
 * @author TMate Software Ltd.
 */
public class SVNRepositoryImpl extends SVNRepository implements ISVNReporter {

    private SVNConnection myConnection;

    private ISVNCredentials myCredentials;

    private String myFullRoot;

    private String myRealm;

    private String myExternalUserName;

    protected SVNRepositoryImpl(SVNRepositoryLocation location) {
        super(location);
    }

    public void testConnection() throws SVNException {
        try {
            openConnection();
        } finally {
            closeConnection();
        }
    }

    public long getLatestRevision() throws SVNException {
        Object[] buffer = new Object[] { "get-latest-rev" };
        try {
            openConnection();
            write("(w())", buffer);
            authenticate();
            buffer = read("[(N)]", buffer);
        } finally {
            closeConnection();
        }
        return SVNReader.getLong(buffer, 0);
    }

    public long getDatedRevision(Date date) throws SVNException {
        if (date == null) {
            date = new Date(System.currentTimeMillis());
        }
        Object[] buffer = new Object[] { "get-dated-rev", date };
        try {
            openConnection();
            write("(w(s))", buffer);
            authenticate();
            buffer = read("[(N)]", buffer);
        } finally {
            closeConnection();
        }
        return SVNReader.getLong(buffer, 0);
    }

    public Map getRevisionProperties(long revision, Map properties)
            throws SVNException {
        assertValidRevision(revision);
        if (properties == null) {
            properties = new HashMap();
        }
        Object[] buffer = new Object[] { "rev-proplist",
                getRevisionObject(revision) };
        try {
            openConnection();
            write("(w(n))", buffer);
            authenticate();
            buffer[0] = properties;
            read("[((*P))]", buffer);
        } finally {
            closeConnection();
        }
        return properties;
    }

    public String getRevisionPropertyValue(long revision, String propertyName)
            throws SVNException {
        assertValidRevision(revision);
        Object[] buffer = new Object[] { "rev-prop",
                getRevisionObject(revision), propertyName };
        try {
            openConnection();
            write("(w(ns))", buffer);
            authenticate();
            buffer = read("[((?S))]", buffer);
        } finally {
            closeConnection();
        }
        return (String) buffer[0];
    }

    public SVNNodeKind checkPath(String path, long revision)
            throws SVNException {
        try {
            openConnection();
            path = getRepositoryPath(path);
            Object[] buffer = new Object[] { "check-path", path,
                    getRevisionObject(revision) };
            write("(w(s(n)))", buffer);
            authenticate();
            read("[(W)]", buffer);

            return SVNNodeKind.parseKind((String) buffer[0]);
        } finally {
            closeConnection();
        }
    }

    public int getLocations(String path, long pegRevision, long[] revisions,
            ISVNLocationEntryHandler handler) throws SVNException {
        assertValidRevision(pegRevision);
        for (int i = 0; i < revisions.length; i++) {
            assertValidRevision(revisions[i]);
        }
        int count = 0;
        try {
            openConnection();
            path = getRepositoryPath(path);
            Object[] buffer = new Object[] { "get-locations", path,
                    getRevisionObject(pegRevision), revisions };
            write("(w(sn(*n)))", buffer);
            authenticate();
            while (true) {
                try {
                    read("(NS)", buffer);
                } catch (SVNException e) {
                    break;
                }
                count++;
                if (handler != null) {
                    long revision = SVNReader.getLong(buffer, 0);
                    String location = SVNReader.getString(buffer, 1);
                    if (location != null) {
                        handler.handleLocationEntry(new SVNLocationEntry(
                                revision, location));
                    }
                }
            }
            read("x", buffer);
            read("[()]", buffer);
        } finally {
            closeConnection();
        }
        return count;
    }

    public long getFile(String path, long revision, Map properties,
            OutputStream contents) throws SVNException {
        Long rev = revision > 0 ? new Long(revision) : null;
        try {
            openConnection();
            Object[] buffer = new Object[] { "get-file",
                    getRepositoryPath(path), rev,
                    Boolean.valueOf(properties != null),
                    Boolean.valueOf(contents != null) };
            write("(w(s(n)ww))", buffer);
            authenticate();
            buffer[2] = properties;
            buffer = read("[((?S)N(*P))]", buffer);
            if (properties != null) {
                properties.put(SVNProperty.REVISION, buffer[1].toString());
                properties.put(SVNProperty.CHECKSUM, buffer[0].toString());
            }
            if (contents != null) {
                Object[] buffer2 = new Object[] { contents };
                read("*I", buffer2);
                read("[()]", buffer2);
            }
            return SVNReader.getLong(buffer, 1);
        } finally {
            closeConnection();
        }
    }

    public long getDir(String path, long revision, Map properties,
            ISVNDirEntryHandler handler) throws SVNException {
        Long rev = getRevisionObject(revision);
        // convert path to path relative to repos root.
        try {
            openConnection();
            path = getRepositoryPath(path);
            Object[] buffer = new Object[] { "get-dir", path, rev,
                    Boolean.valueOf(properties != null),
                    Boolean.valueOf(handler != null) };
            write("(w(s(n)ww))", buffer);
            authenticate();

            buffer[1] = properties;
            buffer = read("[(N(*P)", buffer);
            revision = buffer[0] != null ? SVNReader.getLong(buffer, 0)
                    : revision;
            if (handler != null) {
                buffer[0] = handler;
                read("(*D)))", buffer);
            }
        } finally {
            closeConnection();
        }
        return revision;
    }

    public int getFileRevisions(String path, long sRevision, long eRevision,
            ISVNFileRevisionHandler handler) throws SVNException {
        Long srev = getRevisionObject(sRevision);
        Long erev = getRevisionObject(eRevision);
        int count = 0;
        try {
            openConnection();
            Object[] buffer = new Object[] { "get-file-revs",
                    getRepositoryPath(path), srev, erev };
            write("(w(s(n)(n)))", buffer);
            authenticate();
            buffer = new Object[5];
            while (true) {
                SVNFileRevision fileRevision = null;
                boolean skipDelta = false;
                try {
                    buffer = read("(SN(*P)(*Z)?S", buffer);
                    if (buffer[4] != null && ((String) buffer[4]).length() == 0) {
                        buffer[4] = null;
                        skipDelta = true;
                    } else {
                        read(")", null);
                    }
                    count++;
                } catch (SVNException e) {
                    read("x", buffer);
                    read("[()]", buffer);
                    return count;
                }
                String name = null;
                if (handler != null) {
                    name = (String) buffer[0];
                    long revision = SVNReader.getLong(buffer, 1);
                    Map properties = SVNReader.getMap(buffer, 2);
                    Map propertiesDelta = SVNReader.getMap(buffer, 3);
                    if (name != null) {
                        fileRevision = new SVNFileRevision(name, revision,
                                properties, propertiesDelta);
                    }
                    buffer[2] = null;
                    buffer[3] = null;
                }
                if (handler != null && fileRevision != null) {
                    handler.handleFileRevision(fileRevision);
                }
                if (skipDelta) {
                    continue;
                }
                SVNDiffWindowBuilder builder = SVNDiffWindowBuilder
                        .newInstance();
                while (true) {
                    byte[] line = (byte[]) read("?W?B", buffer)[1];
                    if (line == null) {
                        // may be failure
                        read("[]", buffer);
                        break;
                    } else if (line.length == 0) {
                        // empty line, delta end.
                        break;
                    }
                    builder.accept(line, 0);
                    SVNDiffWindow window = builder.getDiffWindow();
                    if (window != null) {
                        builder.reset(1);
                        OutputStream os = handler.handleDiffWindow(
                                name == null ? path : name, window);
                        long length = window.getNewDataLength();
                        while (length > 0) {
                            byte[] contents = (byte[]) myConnection.read("B",
                                    null)[0];
                            length -= contents.length;
                            try {
                                if (os != null) {
                                    os.write(contents);
                                }
                            } catch (IOException th) {
                                DebugLog.error(th);
                            }
                        }
                        try {
                            if (os != null) {
                                os.close();
                            }
                        } catch (IOException th) {
                            DebugLog.error(th);
                        }
                    }
                }
                handler.handleDiffWindowClosed(name == null ? path : name);
            }
        } finally {
            closeConnection();
        }
    }

    public long log(String[] targetPaths, long startRevision, long endRevision,
            boolean changedPaths, boolean strictNode, long limit,
            ISVNLogEntryHandler handler) throws SVNException {
        long count = 0;
        try {
            openConnection();
            Object[] buffer = new Object[] { "log",
                    getRepositoryPaths(targetPaths),
                    getRevisionObject(startRevision),
                    getRevisionObject(endRevision),
                    Boolean.valueOf(changedPaths), Boolean.valueOf(strictNode),
                    limit > 0 ? new Long(limit) : null };
            write("(w((*s)(n)(n)wwn))", buffer);
            authenticate();
            while (true) {
                try {
                    read("((", buffer);
                    Map changedPathsMap = null;
                    if (changedPaths) {
                        changedPathsMap = handler != null ? new HashMap()
                                : null;
                        while (true) {
                            try {
                                read("(SW(?S?N))", buffer);
                                if (changedPathsMap != null) {
                                    String path = SVNReader
                                            .getString(buffer, 0);
                                    if (path != null && !"".equals(path.trim())) {
                                        String type = SVNReader.getString(
                                                buffer, 1);
                                        String copyPath = SVNReader.getString(
                                                buffer, 2);
                                        long copyRev = SVNReader.getLong(
                                                buffer, 3);
                                        changedPathsMap.put(path,
                                                new SVNLogEntryPath(path, type
                                                        .charAt(0), copyPath,
                                                        copyRev));
                                    }
                                }
                            } catch (SVNException e) {
                                break;
                            }
                        }
                    }
                    read(")N(?S)(?S)(?S))", buffer);
                    count++;
                    if (handler != null) {
                        long revision = SVNReader.getLong(buffer, 0);
                        String author = SVNReader.getString(buffer, 1);
                        Date date = SVNReader.getDate(buffer, 2);
                        String message = SVNReader.getString(buffer, 3);
                        // remove all
                        if (!(limit > 0 && count > limit)) {
                            handler.handleLogEntry(new SVNLogEntry(
                                    changedPathsMap, revision, author, date,
                                    message));
                        }
                    }
                } catch (SVNException e) {
                    read("x", buffer);
                    if (limit <= 0 || (limit > 0 && count <= limit)) {
                        read("[()]", buffer);
                    }
                    return count;
                }
            }
        } finally {
            closeConnection();
        }
    }

    public void update(long revision, String target, boolean recursive,
            ISVNReporterBaton reporter, ISVNEditor editor) throws SVNException {
        target = target == null ? "" : target;
        Object[] buffer = new Object[] { "update", getRevisionObject(revision),
                target, Boolean.valueOf(recursive) };
        try {
            openConnection();
            write("(w((n)sw))", buffer);
            authenticate();
            reporter.report(this);
            authenticate();
            read("*E", new Object[] { editor });
        } finally {
            closeConnection();
        }
    }

    public void update(String url, long revision, String target,
            boolean recursive, ISVNReporterBaton reporter, ISVNEditor editor)
            throws SVNException {
        target = target == null ? "" : target;
        url = getCanonicalURL(url);
        if (url == null) {
            throw new SVNException(url + ": not valid URL");
        }
        Object[] buffer = new Object[] { "switch", getRevisionObject(revision),
                target, Boolean.valueOf(recursive), url };
        try {
            openConnection();
            write("(w((n)sws))", buffer);
            authenticate();
            reporter.report(this);
            authenticate();
            read("*E", new Object[] { editor });
        } finally {
            closeConnection();
        }
    }

    public void diff(String url, long revision, String target,
            boolean ignoreAncestry, boolean recursive,
            ISVNReporterBaton reporter, ISVNEditor editor) throws SVNException {
        diff(url, revision, revision, target, ignoreAncestry, recursive,
                reporter, editor);
    }

    public void diff(String url, long tRevision, long revision, String target,
            boolean ignoreAncestry, boolean recursive,
            ISVNReporterBaton reporter, ISVNEditor editor) throws SVNException {
        target = target == null ? "" : target;
        url = getCanonicalURL(url);
        if (url == null) {
            throw new SVNException(url + ": not valid URL");
        }
        Object[] buffer = new Object[] { "diff", getRevisionObject(tRevision),
                target, Boolean.valueOf(recursive),
                Boolean.valueOf(ignoreAncestry), url };
        try {
            openConnection();
            write("(w((n)swws))", buffer);
            authenticate();
            reporter.report(this);
            authenticate();
            read("*E", new Object[] { editor });
        } finally {
            closeConnection();
        }
    }

    public void status(long revision, String target, boolean recursive,
            ISVNReporterBaton reporter, ISVNEditor editor) throws SVNException {
        target = target == null ? "" : target;
        Object[] buffer = new Object[] { "status", target,
                Boolean.valueOf(recursive), getRevisionObject(revision) };
        try {
            openConnection();
            write("(w(sw(n)))", buffer);
            authenticate();
            reporter.report(this);
            authenticate();
            read("*E", new Object[] { editor });
        } finally {
            closeConnection();
        }
    }

    public void setRevisionPropertyValue(long revision, String propertyName,
            String propertyValue) throws SVNException {
        assertValidRevision(revision);
        Object[] buffer = new Object[] { "change-rev-prop",
                getRevisionObject(revision), propertyName, propertyValue };
        try {
            openConnection();
            write("(w(nss))", buffer);
            authenticate();
            read("[()]", buffer);
        } finally {
            closeConnection();
        }
    }

    public ISVNEditor getCommitEditor(String logMessage, Map locks,
            boolean keepLocks, final ISVNWorkspaceMediator mediator)
            throws SVNException {
        try {
            openConnection();
            if (locks != null) {
                write("(w(s(*l)w))", new Object[] { "commit", logMessage,
                        locks, Boolean.valueOf(keepLocks) });
            } else {
                write("(w(s))", new Object[] { "commit", logMessage });
            }
            authenticate();
            read("[()]", null);
            return new SVNCommitEditor(this, myConnection, mediator,
                    new Runnable() {
                        public void run() {
                            closeConnection();
                        }
                    });
        } catch (SVNException e) {
            closeConnection();
            throw e;
        }
    }

    public SVNLock getLock(String path) throws SVNException {
        try {
            openConnection();
            path = getRepositoryPath(path);
            Object[] buffer = new Object[] { "get-lock", path };
            write("(w(s))", buffer);
            authenticate();
            read("[((?L))]", buffer);
            return (SVNLock) buffer[0];
        } finally {
            closeConnection();
        }
    }

    public SVNLock[] getLocks(String path) throws SVNException {
        try {
            openConnection();
            path = getRepositoryPath(path);
            Object[] buffer = new Object[] { "get-locks", path };
            write("(w(s))", buffer);
            authenticate();
            read("[((*L))]", buffer);
            Collection lockObjects = (Collection) buffer[0];
            return lockObjects == null ? new SVNLock[0]
                    : (SVNLock[]) lockObjects.toArray(new SVNLock[lockObjects
                            .size()]);
        } finally {
            closeConnection();
        }
    }

    public SVNLock setLock(String path, String comment, boolean force,
            long revision) throws SVNException {
        try {
            openConnection();
            path = getRepositoryPath(path);
            Object[] buffer = new Object[] { "lock", path, comment,
                    Boolean.valueOf(force), getRevisionObject(revision) };
            write("(w(s(s)w(n)))", buffer);
            authenticate();
            read("[(L)]", buffer);
            return (SVNLock) buffer[0];
        } finally {
            closeConnection();
        }
    }

    public void removeLock(String path, String id, boolean force)
            throws SVNException {
        try {
            if (id == null) {
                SVNLock lock = getLock(path);
                if (lock == null) {
                    throw new SVNException("'" + path + "' is not locked");
                }
                id = lock.getID();
            }
            openConnection();
            path = getRepositoryPath(path);
            Object[] buffer = new Object[] { "unlock", path, id,
                    Boolean.valueOf(force) };
            write("(w(s(s)w))", buffer);
            authenticate();
            read("[()]", buffer);
        } finally {
            closeConnection();
        }
    }

    public SVNDirEntry info(String path, long revision) throws SVNException {
        try {
            openConnection();
            path = getRepositoryPath(path);
            Object[] buffer = new Object[] { "stat", path,
                    getRevisionObject(revision) };
            write("(w(s(n)))", buffer);
            authenticate();
            read("[((?F))]", buffer);
            SVNDirEntry entry = (SVNDirEntry) buffer[0];
            if (entry != null) {
                entry.setName(PathUtil.tail(path));
            }
            return (SVNDirEntry) buffer[0];
        } finally {
            closeConnection();
        }
    }

    void updateCredentials(String uuid, String root) {
        if (getRepositoryRoot() != null) {
            return;
        }
        root = root == null ? getRepositoryRoot() : root;
        uuid = uuid == null ? getRepositoryUUID() : uuid;
        // remove url path from root.
        if (root != null) {
            myFullRoot = root;
            root = root.startsWith("svn://") ? root
                    .substring("svn://".length()) : root.substring("svn+ssh://"
                    .length());
            root = PathUtil.removeTrailingSlash(root);
            if (root.indexOf('/') >= 0) {
                root = root.substring(root.indexOf('/'));
            } else {
                root = "/";
            }
        }
        DebugLog.log("root: " + root);
        DebugLog.log("full root: " + myFullRoot);
        setRepositoryCredentials(uuid, root, myFullRoot);
    }

    private void openConnection() throws SVNException {
        lock();
        ISVNConnector connector = SVNRepositoryFactoryImpl
                .getConnectorFactory().createConnector(this);
        myConnection = new SVNConnection(connector, getLocation(),
                getAuthenticationManager());
        try {
            myConnection.open(this);
            authenticate();
        } finally {
            myRealm = myConnection.getRealm();
        }
    }

    public String getRealm() {
        return myRealm;
    }

    void authenticate() throws SVNException {
        if (myConnection != null) {
            myConnection.authenticate(this, myCredentials);
        }
    }

    private void closeConnection() {
        if (myConnection != null) {
            try {
                myConnection.close();
            } catch (SVNException e) {
                //
            } finally {
                myConnection = null;
            }
        }
        unlock();
    }

    private void write(String template, Object[] values) throws SVNException {
        if (myConnection == null) {
            throw new SVNException("connection is closed, can't write");
        }
        myConnection.write(template, values);
    }

    private Object[] read(String template, Object[] values) throws SVNException {
        if (myConnection == null) {
            throw new SVNException("connection is closed, can't read");
        }
        return myConnection.read(template, values);
    }

    /*
     * ISVNReporter methods
     */

    public void setPath(String path, String lockToken, long revision,
            boolean startEmpty) throws SVNException {
        assertValidRevision(revision);
        if (lockToken == null) {
            write("(w(snw))", new Object[] { "set-path", path,
                    getRevisionObject(revision), Boolean.valueOf(startEmpty) });
        } else {
            write("(w(snw(s)))", new Object[] { "set-path", path,
                    getRevisionObject(revision), Boolean.valueOf(startEmpty),
                    lockToken });
        }
    }

    public void deletePath(String path) throws SVNException {
        write("(w(s))", new Object[] { "delete-path", path });
    }

    public void linkPath(SVNRepositoryLocation repository, String path,
            String lockToken, long revison, boolean startEmtpy)
            throws SVNException {
        assertValidRevision(revison);
        if (lockToken == null) {
            write("(w(ssnw))", new Object[] { "link-path", path,
                    repository.toString(), getRevisionObject(revison),
                    Boolean.valueOf(startEmtpy) });
        } else {
            write("(w(ssnw(s)))", new Object[] { "link-path", path,
                    repository.toString(), getRevisionObject(revison),
                    Boolean.valueOf(startEmtpy), lockToken });
        }
    }

    public void finishReport() throws SVNException {
        write("(w())", new Object[] { "finish-report" });
    }

    public void abortReport() throws SVNException {
        write("(w())", new Object[] { "abort-report" });
    }

    public String[] getRepositoryPaths(String[] paths) {
        if (paths == null || paths.length == 0) {
            return paths;
        }
        String[] fullPaths = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            fullPaths[i] = getRepositoryPath(paths[i]);
        }
        return fullPaths;
    }

    public String getRepositoryPath(String path) {
        if (path != null && path.startsWith("/")) {
            // assuming it is full path.
            return path;
        }
        String fullPath = PathUtil.append(PathUtil.decode(getLocation()
                .getPath()), path);
        // substract root path
        if (fullPath.startsWith(getRepositoryRoot())) {
            fullPath = fullPath.substring(getRepositoryRoot().length());
        }
        if (!fullPath.startsWith("/")) {
            fullPath = "/" + fullPath;
        }
        return PathUtil.removeTrailingSlash(PathUtil.isEmpty(fullPath) ? "/"
                : fullPath);
    }

    public void setCredentials(ISVNCredentials credentials) {
        myCredentials = credentials;
    }

    public ISVNCredentials getCredentials() {
        return myCredentials;
    }

    public String getFullRoot() {
        return myFullRoot;
    }

    public void setExternalUserName(String userName) {
        myExternalUserName = userName;
    }

    public String getExternalUserName() {
        return myExternalUserName;
    }
}
