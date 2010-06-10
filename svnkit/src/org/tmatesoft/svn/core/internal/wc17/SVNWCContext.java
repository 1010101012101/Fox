/*
 * ====================================================================
 * Copyright (c) 2004-2010 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://svnkit.com/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.internal.wc17;

import java.io.File;

import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLock;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.internal.wc.admin.SVNEntry;
import org.tmatesoft.svn.core.internal.wc.db.SVNWCSchedule;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb;
import org.tmatesoft.svn.core.internal.wc17.db.SVNWCDb;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbAdditionInfo;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbBaseInfo;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbDeletionInfo;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbInfo;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbKind;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbLock;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbOpenMode;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbStatus;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbAdditionInfo.AdditionInfoField;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbBaseInfo.BaseInfoField;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbDeletionInfo.DeletionInfoField;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbInfo.InfoField;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.ISVNEventHandler;
import org.tmatesoft.svn.core.wc.ISVNOptions;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNStatus;
import org.tmatesoft.svn.core.wc.SVNStatusType;
import org.tmatesoft.svn.core.wc.SVNTreeConflictDescription;
import org.tmatesoft.svn.util.SVNLogType;

/**
 * @version 1.4
 * @author TMate Software Ltd.
 */
public class SVNWCContext {

    private ISVNWCDb db;
    private boolean closeDb;
    private ISVNEventHandler eventHandler;

    public SVNWCContext(ISVNOptions config, ISVNEventHandler eventHandler) throws SVNException {
        this(WCDbOpenMode.ReadWrite, config, true, true, eventHandler);
    }

    public SVNWCContext(WCDbOpenMode mode, ISVNOptions config, boolean autoUpgrade, boolean enforceEmptyWQ, ISVNEventHandler eventHandler) throws SVNException {
        this.db = new SVNWCDb();
        this.db.open(mode, config, autoUpgrade, enforceEmptyWQ);
        this.closeDb = true;
        this.eventHandler = eventHandler;
    }

    public SVNWCContext(ISVNWCDb db, ISVNEventHandler eventHandler) {
        this.db = db;
        this.closeDb = false;
        this.eventHandler = eventHandler;
    }

    public void close() throws SVNException {
        if (closeDb) {
            db.close();
        }
    }

    public ISVNWCDb getDb() {
        return db;
    }

    public void checkCancelled() throws SVNCancelException {
        if (eventHandler != null) {
            eventHandler.checkCancelled();
        }
    }

    public SVNNodeKind getNodeKind(File path, boolean showHidden) throws SVNException {
        try {
            /* Make sure hidden nodes return SVNNodeKind.NONE. */
            if (!showHidden) {
                final boolean hidden = db.isNodeHidden(path);
                if (hidden) {
                    return SVNNodeKind.NONE;
                }
            }
            final WCDbKind kind = db.readKind(path, false);
            if (kind == null) {
                return SVNNodeKind.NONE;
            }
            return kind.toNodeKind();
        } catch (SVNException e) {
            if (e.getErrorMessage().getErrorCode() == SVNErrorCode.WC_PATH_NOT_FOUND) {
                return SVNNodeKind.NONE;
            }
            throw e;
        }
    }

    public boolean isNodeAdded(File path) throws SVNException {
        final WCDbInfo info = db.readInfo(path, InfoField.status);
        final WCDbStatus status = info.status;
        return status == WCDbStatus.Added || status == WCDbStatus.ObstructedAdd;
    }

    /** Equivalent to the old notion of "entry->schedule == schedule_replace" */
    public boolean isNodeReplaced(File path) throws SVNException {
        final WCDbInfo info = db.readInfo(path, InfoField.status, InfoField.baseShadowed);
        final WCDbStatus status = info.status;
        final boolean baseShadowed = info.baseShadowed;
        WCDbStatus baseStatus = null;
        if (baseShadowed) {
            final WCDbBaseInfo baseInfo = db.getBaseInfo(path, BaseInfoField.status);
            baseStatus = baseInfo.status;
        }
        return ((status == WCDbStatus.Added || status == WCDbStatus.ObstructedAdd) && baseShadowed && baseStatus != WCDbStatus.NotPresent);
    }

    public long getRevisionNumber(SVNRevision revision, long[] latestRevisionNumber, SVNRepository repository, File path) throws SVNException {
        if (repository == null && (revision == SVNRevision.HEAD || revision.getDate() != null)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.CLIENT_RA_ACCESS_REQUIRED);
            SVNErrorManager.error(err, SVNLogType.WC);
        }
        if (revision.getNumber() >= 0) {
            return revision.getNumber();
        } else if (revision.getDate() != null) {
            return repository.getDatedRevision(revision.getDate());
        } else if (revision == SVNRevision.HEAD) {
            if (latestRevisionNumber != null && latestRevisionNumber.length > 0 && SVNRevision.isValidRevisionNumber(latestRevisionNumber[0])) {
                return latestRevisionNumber[0];
            }
            long latestRevision = repository.getLatestRevision();
            if (latestRevisionNumber != null && latestRevisionNumber.length > 0) {
                latestRevisionNumber[0] = latestRevision;
            }
            return latestRevision;
        } else if (!revision.isValid()) {
            return -1;
        } else if (revision == SVNRevision.WORKING || revision == SVNRevision.BASE) {
            if (path == null) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.CLIENT_VERSIONED_PATH_REQUIRED);
                SVNErrorManager.error(err, SVNLogType.WC);
            }
            long revnum = -1;
            try {
                revnum = getNodeCommitBaseRev(path);
            } catch (SVNException e) {
                /*
                 * Return the same error as older code did (before and at
                 * r935091). At least svn_client_proplist3 promises
                 * SVN_ERR_ENTRY_NOT_FOUND.
                 */
                if (e.getErrorMessage().getErrorCode() == SVNErrorCode.WC_PATH_NOT_FOUND) {
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND);
                    SVNErrorManager.error(err, SVNLogType.WC);
                } else {
                    throw e;
                }
            }
            if (!SVNRevision.isValidRevisionNumber(revnum)) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.CLIENT_BAD_REVISION, "Path ''{0}'' has no committed revision", path);
                SVNErrorManager.error(err, SVNLogType.WC);
            }
            return revnum;
        } else if (revision == SVNRevision.COMMITTED || revision == SVNRevision.PREVIOUS) {
            if (path == null) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.CLIENT_VERSIONED_PATH_REQUIRED);
                SVNErrorManager.error(err, SVNLogType.WC);
            }
            final WCDbInfo info = getNodeChangedInfo(path);
            if (!SVNRevision.isValidRevisionNumber(info.changedRev)) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.CLIENT_BAD_REVISION, "Path ''{0}'' has no committed revision", path);
                SVNErrorManager.error(err, SVNLogType.WC);

            }
            return revision == SVNRevision.PREVIOUS ? info.changedRev - 1 : info.changedRev;
        } else {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.CLIENT_BAD_REVISION, "Unrecognized revision type requested for ''{0}''", path != null ? path : (Object) repository.getLocation());
            SVNErrorManager.error(err, SVNLogType.WC);
        }
        return -1;
    }

    private WCDbInfo getNodeChangedInfo(File path) throws SVNException {
        return db.readInfo(path, InfoField.changedRev, InfoField.changedDate, InfoField.changedAuthor);
    }

    private long getNodeCommitBaseRev(File local_abspath) throws SVNException {
        final WCDbInfo info = db.readInfo(local_abspath, InfoField.status, InfoField.revision, InfoField.baseShadowed);
        /*
         * If this returned a valid revnum, there is no WORKING node. The node
         * is cleanly checked out, no modifications, copies or replaces.
         */
        long commitBaseRevision = info.revision;
        if (SVNRevision.isValidRevisionNumber(commitBaseRevision)) {
            return commitBaseRevision;
        }
        final WCDbStatus status = info.status;
        final boolean baseShadowed = info.baseShadowed;
        if (status == WCDbStatus.Added) {
            /*
             * If the node was copied/moved-here, return the copy/move source
             * revision (not this node's base revision). If it's just added,
             * return INVALID_REVNUM.
             */
            final WCDbAdditionInfo addInfo = db.scanAddition(local_abspath, AdditionInfoField.originalRevision);
            commitBaseRevision = addInfo.originalRevision;
            if (!SVNRevision.isValidRevisionNumber(commitBaseRevision) && baseShadowed) {
                /*
                 * It is a replace that does not feature a copy/move-here.
                 * Return the revert-base revision.
                 */
                commitBaseRevision = getNodeBaseRev(local_abspath);
            }
        } else if (status == WCDbStatus.Deleted) {
            final WCDbDeletionInfo delInfo = db.scanDeletion(local_abspath, DeletionInfoField.workDelAbsPath);
            final File workDelAbspath = delInfo.workDelAbsPath;
            if (workDelAbspath != null) {
                /*
                 * This is a deletion within a copied subtree. Get the
                 * copied-from revision.
                 */
                final File parentAbspath = workDelAbspath.getParentFile();
                final WCDbInfo parentInfo = db.readInfo(parentAbspath, InfoField.status);
                final WCDbStatus parentStatus = parentInfo.status;
                assert (parentStatus == WCDbStatus.Added || parentStatus == WCDbStatus.ObstructedAdd);
                final WCDbAdditionInfo parentAddInfo = db.scanAddition(parentAbspath, AdditionInfoField.originalRevision);
                commitBaseRevision = parentAddInfo.originalRevision;
            } else
                /* This is a normal delete. Get the base revision. */
                commitBaseRevision = getNodeBaseRev(local_abspath);
        }
        return commitBaseRevision;
    }

    private long getNodeBaseRev(File local_abspath) throws SVNException {
        final WCDbInfo info = db.readInfo(local_abspath, InfoField.status, InfoField.revision, InfoField.baseShadowed);
        long baseRevision = info.revision;
        if (SVNRevision.isValidRevisionNumber(baseRevision)) {
            return baseRevision;
        }
        final boolean baseShadowed = info.baseShadowed;
        if (baseShadowed) {
            /* The node was replaced with something else. Look at the base. */
            final WCDbBaseInfo baseInfo = db.getBaseInfo(local_abspath, BaseInfoField.revision);
            baseRevision = baseInfo.revision;
        }
        return baseRevision;
    }

    public SVNStatus assembleStatus(File path, SVNURL parentReposRootUrl, File parentReposRelPath, SVNNodeKind pathKind, boolean pathSpecial, boolean getAll, boolean isIgnored,
            SVNLock repositoryLock, SVNURL repositoryRoot, SVNWCContext wCContext) throws SVNException {

        boolean locked_p = false;

        /* Defaults for two main variables. */
        SVNStatusType final_text_status = SVNStatusType.STATUS_NORMAL;
        SVNStatusType final_prop_status = SVNStatusType.STATUS_NONE;
        /* And some intermediate results */
        SVNStatusType pristine_text_status = SVNStatusType.STATUS_NONE;
        SVNStatusType pristine_prop_status = SVNStatusType.STATUS_NONE;

        SVNEntry entry = getEntry(path, false, SVNNodeKind.UNKNOWN, false);

        /*
         * Find out whether the path is a tree conflict victim. This function
         * will set tree_conflict to NULL if the path is not a victim.
         */
        SVNTreeConflictDescription tree_conflict = db.opReadTreeConflict(path);
        WCDbInfo info = db.readInfo(path, InfoField.status, InfoField.kind, InfoField.revision, InfoField.reposRelPath, InfoField.reposRootUrl, InfoField.changedRev, InfoField.changedDate,
                InfoField.changedAuthor, InfoField.changelist, InfoField.baseShadowed, InfoField.conflicted, InfoField.lock);

        SVNURL url = getNodeUrl(path);
        boolean file_external_p = isFileExternal(path);

        /*
         * File externals are switched files, but they are not shown as such. To
         * be switched it must have both an URL and a parent with an URL, at the
         * very least.
         */
        boolean switched_p = !file_external_p ? isSwitched(path) : false;

        /*
         * Examine whether our directory metadata is present, and compensate if
         * it is missing.
         *
         * There are a several kinds of obstruction that we detect here:
         *
         * - versioned subdir is missing - the versioned subdir's admin area is
         * missing - the versioned subdir has been replaced with a file/symlink
         *
         * Net result: the target is obstructed and the metadata is unavailable.
         *
         * Note: wc_db can also detect a versioned file that has been replaced
         * with a versioned subdir (moved from somewhere). We don't look for
         * that right away because the file's metadata is still present, so we
         * can examine properties and conflicts and whatnot.
         *
         * ### note that most obstruction concepts disappear in single-db mode
         */
        if (info.kind == WCDbKind.Dir) {
            if (info.status == WCDbStatus.Incomplete) {
                /* Highest precedence. */
                final_text_status = SVNStatusType.STATUS_INCOMPLETE;
            } else if (info.status == WCDbStatus.ObstructedDelete) {
                /* Deleted directories are never reported as missing. */
                if (pathKind == SVNNodeKind.NONE)
                    final_text_status = SVNStatusType.STATUS_DELETED;
                else
                    final_text_status = SVNStatusType.STATUS_OBSTRUCTED;
            } else if (info.status == WCDbStatus.Obstructed || info.status == WCDbStatus.ObstructedAdd) {
                /*
                 * A present or added directory should be on disk, so it is
                 * reported missing or obstructed.
                 */
                if (pathKind == SVNNodeKind.NONE)
                    final_text_status = SVNStatusType.STATUS_MISSING;
                else
                    final_text_status = SVNStatusType.STATUS_OBSTRUCTED;
            }
        }

        /*
         * If FINAL_TEXT_STATUS is still normal, after the above checks, then we
         * should proceed to refine the status.
         *
         * If it was changed, then the subdir is incomplete or
         * missing/obstructed. It means that no further information is
         * available, and we should skip all this work.
         */
        SVNProperties pristineProperties = null;
        SVNProperties actualProperties = null;
        if (final_text_status == SVNStatusType.STATUS_NORMAL) {
            boolean has_props;
            boolean prop_modified_p = false;
            boolean text_modified_p = false;
            boolean wc_special;

            /* Implement predecence rules: */

            /*
             * 1. Set the two main variables to "discovered" values first (M,
             * C). Together, these two stati are of lowest precedence, and C has
             * precedence over M.
             */

            /* Does the entry have props? */
            pristineProperties = db.readPristineProperties(path);
            actualProperties = db.readProperties(path);
            has_props = ((pristineProperties != null && !pristineProperties.isEmpty()) || (actualProperties != null && !actualProperties.isEmpty()));
            if (has_props) {
                final_prop_status = SVNStatusType.STATUS_NORMAL;
                /*
                 * If the entry has a property file, see if it has local
                 * changes.
                 */
                prop_modified_p = isPropertiesDiff(pristineProperties, actualProperties);
            }

            /* Record actual property status */
            pristine_prop_status = prop_modified_p ? SVNStatusType.STATUS_MODIFIED : SVNStatusType.STATUS_NORMAL;

            if (has_props) {
                wc_special = isSpecial(path);
            } else {
                wc_special = false;
            }

            /* If the entry is a file, check for textual modifications */
            if ((info.kind == WCDbKind.File) && (wc_special == pathSpecial)) {

                text_modified_p = isTextModified(path, false, true);
                /* Record actual text status */
                pristine_text_status = text_modified_p ? SVNStatusType.STATUS_MODIFIED : SVNStatusType.STATUS_NORMAL;
            }

            if (text_modified_p)
                final_text_status = SVNStatusType.STATUS_MODIFIED;

            if (prop_modified_p)
                final_prop_status = SVNStatusType.STATUS_MODIFIED;

            if (entry.getPropRejectFile() != null || entry.getConflictOld() != null || entry.getConflictNew() != null || entry.getConflictWorking() != null) {
                boolean[] text_conflict_p = {
                    false
                };
                boolean[] prop_conflict_p = {
                    false
                };

                /*
                 * The entry says there was a conflict, but the user might have
                 * marked it as resolved by deleting the artifact files, so
                 * check for that.
                 */
                getIsConflicted(path, text_conflict_p, prop_conflict_p, null);

                if (text_conflict_p[0])
                    final_text_status = SVNStatusType.STATUS_CONFLICTED;
                if (prop_conflict_p[0])
                    final_prop_status = SVNStatusType.STATUS_CONFLICTED;
            }

            /*
             * 2. Possibly overwrite the text_status variable with "scheduled"
             * states from the entry (A, D, R). As a group, these states are of
             * medium precedence. They also override any C or M that may be in
             * the prop_status field at this point, although they do not
             * override a C text status.
             */

            /*
             * ### db_status, base_shadowed, and fetching base_status can ###
             * fully replace entry->schedule here.
             */

            if (SVNWCSchedule.ADD.name().equalsIgnoreCase(entry.getSchedule()) && final_text_status != SVNStatusType.STATUS_CONFLICTED) {
                final_text_status = SVNStatusType.STATUS_ADDED;
                final_prop_status = SVNStatusType.STATUS_NONE;
            }

            else if (SVNWCSchedule.REPLACE.name().equalsIgnoreCase(entry.getSchedule()) && final_text_status != SVNStatusType.STATUS_CONFLICTED) {
                final_text_status = SVNStatusType.STATUS_REPLACED;
                final_prop_status = SVNStatusType.STATUS_NONE;
            }

            else if (SVNWCSchedule.DELETE.name().equalsIgnoreCase(entry.getSchedule()) && final_text_status != SVNStatusType.STATUS_CONFLICTED) {
                final_text_status = SVNStatusType.STATUS_DELETED;
                final_prop_status = SVNStatusType.STATUS_NONE;
            }

            /*
             * 3. Highest precedence:
             *
             * a. check to see if file or dir is just missing, or incomplete.
             * This overrides every possible stateexcept* deletion. (If
             * something is deleted or scheduled for it, we don't care if the
             * working file exists.)
             *
             * b. check to see if the file or dir is present in the file system
             * as the same kind it was versioned as.
             *
             * 4. Check for locked directory (only for directories).
             */

            if (entry.isIncomplete() && (final_text_status != SVNStatusType.STATUS_DELETED) && (final_text_status != SVNStatusType.STATUS_ADDED)) {
                final_text_status = SVNStatusType.STATUS_INCOMPLETE;
            } else if (pathKind == SVNNodeKind.NONE) {
                if (final_text_status != SVNStatusType.STATUS_DELETED)
                    final_text_status = SVNStatusType.STATUS_MISSING;
            }
            /*
             * ### We can do this db_kind to node_kind translation since the
             * cases where db_kind would have been unknown are treated as
             * unversioned paths and thus have already returned.
             */
            else if (pathKind != (info.kind == WCDbKind.Dir ? SVNNodeKind.DIR : SVNNodeKind.FILE)) {
                final_text_status = SVNStatusType.STATUS_OBSTRUCTED;
            }

            else if (wc_special != pathSpecial) {
                final_text_status = SVNStatusType.STATUS_OBSTRUCTED;
            }

            if (pathKind == SVNNodeKind.DIR && info.kind == WCDbKind.Dir) {
                locked_p = db.isWCLocked(path);
            }
        }

        /*
         * 5. Easy out: unless we're fetching -every- entry, don't bother to
         * allocate a struct for an uninteresting entry.
         */

        if (!getAll)
            if (((final_text_status == SVNStatusType.STATUS_NONE) || (final_text_status == SVNStatusType.STATUS_NORMAL))
                    && ((final_prop_status == SVNStatusType.STATUS_NONE) || (final_prop_status == SVNStatusType.STATUS_NORMAL)) && (!locked_p) && (!switched_p) && (!file_external_p)
                    && (info.lock == null) && (repositoryLock == null) && (info.changelist == null) && (tree_conflict == null)) {
                return null;
            }

        /* 6. Build and return a status structure. */

        SVNLock lock = null;
        if (info.lock != null) {
            final WCDbLock wcdbLock = info.lock;
            lock = new SVNLock(path.toString(), wcdbLock.token, wcdbLock.owner, wcdbLock.comment, wcdbLock.date, null);
        }

        SVNStatus status = new SVNStatus(url, path, info.kind.toNodeKind(), SVNRevision.create(info.revision), SVNRevision.create(info.changedRev), info.changedDate, info.changedAuthor,
                final_text_status, final_prop_status, SVNStatusType.STATUS_NONE, SVNStatusType.STATUS_NONE, locked_p, entry.isCopied(), switched_p, file_external_p, new File(entry.getConflictNew()),
                new File(entry.getConflictOld()), new File(entry.getConflictWorking()), new File(entry.getPropRejectFile()), entry.getCopyFromURL(), SVNRevision.create(entry.getCopyFromRevision()),
                repositoryLock, lock, actualProperties.asMap(), info.changelist, db.WC_FORMAT_17, tree_conflict);
        status.setEntry(entry);

        return status;

    }

    private void getIsConflicted(File path, boolean[] textConflictP, boolean[] propConflictP, Object object) {
    }

    private boolean isSpecial(File path) {
        final String property = getProperty(path, SVNProperty.SPECIAL);
        return property != null;
    }

    private boolean isPropertiesDiff(SVNProperties pristineProperties, SVNProperties actualProperties) throws SVNException {
        if (pristineProperties == null && actualProperties == null) {
            return false;
        }
        if (pristineProperties == null || actualProperties == null) {
            return true;
        }
        final SVNProperties diff = actualProperties.compareTo(pristineProperties);
        return diff != null && !diff.isEmpty();
    }

    private boolean isTextModified(File path, boolean force_comparison, boolean compare_textbases) throws SVNException {
        return false;
    }

    private boolean isSwitched(File path) {
        // TODO
        return false;
    }

    private boolean isFileExternal(File path) {
        // TODO
        return false;
    }

    private SVNURL getNodeUrl(File path) {
        return null;
    }

    public boolean isAdminDirectory(String name) {
        return name != null && (SVNFileUtil.isWindows) ? SVNFileUtil.getAdminDirectoryName().equalsIgnoreCase(name) : SVNFileUtil.getAdminDirectoryName().equals(name);
    }

    public String getProperty(File path, String propertyName) {
        return null;
    }

    public SVNStatus assembleUnversioned(File nodeAbsPath, SVNNodeKind pathKind, boolean isIgnored) {
        return null;
    }

    public SVNEntry getEntry(File nodeAbsPath, boolean b, SVNNodeKind unknown, boolean c) throws SVNException {
        return null;
    }

}