/*
 * ASTRID: Android's Simple Task Recording Dashboard
 *
 * Copyright (c) 2009 Tim Su
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package com.timsu.astrid.sync;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.util.Log;

import com.timsu.astrid.R;
import com.timsu.astrid.data.alerts.AlertController;
import com.timsu.astrid.data.sync.SyncDataController;
import com.timsu.astrid.data.sync.SyncMapping;
import com.timsu.astrid.data.tag.TagController;
import com.timsu.astrid.data.tag.TagIdentifier;
import com.timsu.astrid.data.tag.TagModelForView;
import com.timsu.astrid.data.task.TaskController;
import com.timsu.astrid.data.task.TaskIdentifier;
import com.timsu.astrid.data.task.TaskModelForSync;
import com.timsu.astrid.utilities.DialogUtilities;
import com.timsu.astrid.utilities.Notifications;
import com.timsu.astrid.utilities.Preferences;

/** A service that synchronizes with Astrid
 *
 * @author timsu
 *
 */
public abstract class SynchronizationProvider {

    private int id;
    static ProgressDialog progressDialog;
    private Handler syncHandler;
    protected Synchronizer synchronizer;

    public SynchronizationProvider(int id) {
        this.id = id;
    }

    /** Called off the UI thread. does some setup and then invokes implemented
     * synchronize method
     * @param activity
     * @param caller
     */
    void synchronizeService(final Context activity, Synchronizer caller) {
        this.synchronizer = caller;

        if(!isBackgroundService()) {
        	syncHandler = new Handler();
	        SynchronizationProvider.progressDialog = new ProgressDialog(activity);
	        progressDialog.setIcon(android.R.drawable.ic_dialog_alert);
	        progressDialog.setTitle("Synchronization");
	        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
	        progressDialog.setMax(100);
	        progressDialog.setMessage("Checking Authorization...");
	        progressDialog.setProgress(0);
	        progressDialog.setCancelable(false);
	        progressDialog.show();
        }

        synchronize(activity);
    }

    /** Synchronize with the service */
    protected abstract void synchronize(Context activity);

    /** Called when user requests a data clear */
    abstract void clearPersonalData(Context activity);

    /** Get this service's id */
    public int getId() {
        return id;
    }

    /** Gets this service's name */
    abstract String getName();

    // --- utilities

    /** Check whether this synchronization request is running in the background
     * @return true if it's running as a background service
     */
    protected boolean isBackgroundService() {
    	return synchronizer.isService();
    }

    /** Utility method for showing synchronization errors. If message is null,
     *  the contents of the throwable is displayed.
     */
    void showError(final Context context, Throwable e, String message) {
        Log.e("astrid", "Synchronization Error", e);

        if(isBackgroundService())
        	return;

        Resources r = context.getResources();
        final String messageToDisplay;
        if(message == null) {
            messageToDisplay = r.getString(R.string.sync_error) + " " +
                e.toString() + " - " + e.getStackTrace()[1];
        } else {
            messageToDisplay = message;
        }
        syncHandler.post(new Runnable() {
            public void run() {
                if(progressDialog != null)
                    progressDialog.dismiss();
                DialogUtilities.okDialog(context, messageToDisplay, null);
            }
        });
    }

    /** Utility method to update the UI if we're an active sync, or output
     * to console if we're a background sync.
     */
    protected void postUpdate(Runnable updater) {
    	if(isBackgroundService()) {
    		// only run jobs if they can actually be processed
    		if(updater instanceof ProgressLabelUpdater)
    			updater.run();
    	} else {
    		syncHandler.post(updater);
    	}
    }

    // --- synchronization logic

    /** interface to assist with synchronization */
    protected interface SynchronizeHelper {
        /** Push the given task to the remote server.
         *
         * @param task task proxy to push
         * @param remoteTask remote task that we merged with, or null
         * @param mapping local/remote mapping.
         */
        void pushTask(TaskProxy task, TaskProxy remoteTask,
                SyncMapping mapping) throws IOException;

        /** Create a task on the remote server. This is followed by a call of
         * pushTask on the id in question.
         *
         * @return task to create
         * @return remote id
         */
        String createTask(TaskModelForSync task) throws IOException;

        /** Fetch remote task. Used to re-read merged tasks
         *
         * @param task TaskProxy of the original task
         * @return new TaskProxy
         */
        TaskProxy refetchTask(TaskProxy task) throws IOException;

        /** Delete the task from the remote server
         *
         * @param mapping mapping to delete
         */
        void deleteTask(SyncMapping mapping) throws IOException;
    }

    /** Helper to synchronize remote tasks with our local database.
     *
     * This initiates the following process:
     * 1. local changes are read
     * 2. remote changes are read
     * 3. local tasks are merged with remote changes and pushed across
     * 4. remote changes are then read in
     *
     * @param remoteTasks remote tasks that have been updated
     * @return local tasks that need to be pushed across
     */
    protected void synchronizeTasks(final Context context, LinkedList<TaskProxy>
            remoteTasks, SynchronizeHelper helper) throws IOException {
        final SyncStats stats = new SyncStats();
        final StringBuilder log = new StringBuilder();

        SyncDataController syncController = synchronizer.getSyncController(context);
        TaskController taskController = synchronizer.getTaskController(context);
        TagController tagController = synchronizer.getTagController(context);
        AlertController alertController = synchronizer.getAlertController(context);
        SyncData data = new SyncData(context, remoteTasks);

        // 1. CREATE: grab tasks without a sync mapping and create them remotely
        log.append(">> on remote server:\n");
        for(TaskIdentifier taskId : data.newlyCreatedTasks) {
            TaskModelForSync task = taskController.fetchTaskForSync(taskId);
            postUpdate(new ProgressLabelUpdater("Sending local task: " +
                    task.getName()));
            postUpdate(new ProgressUpdater(stats.remoteCreatedTasks,
                    data.newlyCreatedTasks.size()));

            /* If there exists an incoming remote task with the same name and
             * no mapping, we don't want to create this on the remote server.
             * Instead, we create a mapping and do an update. */
            if(data.newRemoteTasks.containsKey(task.getName())) {
                TaskProxy remoteTask = data.newRemoteTasks.get(task.getName());
                SyncMapping mapping = new SyncMapping(taskId, getId(),
                        remoteTask.getRemoteId());
                syncController.saveSyncMapping(mapping);
                data.localChanges.add(mapping);
                data.remoteChangeMap.put(taskId, remoteTask);
                data.localIdToSyncMapping.put(taskId, mapping);
                continue;
            }

            String remoteId = helper.createTask(task);
            SyncMapping mapping = new SyncMapping(taskId, getId(), remoteId);
            syncController.saveSyncMapping(mapping);
            data.localIdToSyncMapping.put(taskId, mapping);

            TaskProxy localTask = new TaskProxy(getId(), remoteId, false);
            localTask.readFromTaskModel(task);
            localTask.readTagsFromController(taskId, tagController, data.tags);
            helper.pushTask(localTask, null, mapping);

            // update stats
            log.append("added '" + task.getName() + "'\n");
            stats.remoteCreatedTasks++;
        }

        // 2. DELETE: find deleted tasks and remove them from the list
        postUpdate(new ProgressLabelUpdater("Sending locally deleted tasks"));
        for(TaskIdentifier taskId : data.deletedTasks) {
            SyncMapping mapping = data.localIdToSyncMapping.get(taskId);
            syncController.deleteSyncMapping(mapping);
            helper.deleteTask(mapping);

            // remove it from data structures
            data.localChanges.remove(mapping);
            data.localIdToSyncMapping.remove(taskId);
            data.remoteIdToSyncMapping.remove(mapping);
            data.remoteChangeMap.remove(taskId);

            // update stats
            log.append("deleted id #" + taskId.getId() + "\n");
            stats.remoteDeletedTasks++;
            postUpdate(new ProgressUpdater(stats.remoteDeletedTasks,
                    data.deletedTasks.size()));
        }

        // 3. UPDATE: for each updated local task
        for(SyncMapping mapping : data.localChanges) {
            TaskProxy localTask = new TaskProxy(getId(), mapping.getRemoteId(),
                    false);
            TaskModelForSync task = taskController.fetchTaskForSync(
                    mapping.getTask());
            localTask.readFromTaskModel(task);
            localTask.readTagsFromController(task.getTaskIdentifier(),
                    tagController, data.tags);

            postUpdate(new ProgressLabelUpdater("Sending local task: " +
                    task.getName()));
            postUpdate(new ProgressUpdater(stats.remoteUpdatedTasks,
                    data.localChanges.size()));

            // if there is a conflict, merge
            TaskProxy remoteConflict = null;
            if(data.remoteChangeMap.containsKey(mapping.getTask())) {
                remoteConflict = data.remoteChangeMap.get(mapping.getTask());
                localTask.mergeWithOther(remoteConflict);
                stats.mergedTasks++;
            }

            try {
                helper.pushTask(localTask, remoteConflict, mapping);
                if(remoteConflict != null)
                    log.append("merged '" + task.getName() + "'\n");
                else
                    log.append("updated '" + task.getName() + "'\n");
            } catch (Exception e) {
                Log.e("astrid", "Exception pushing task", e);
                log.append("error sending '" + task.getName() + "'\n");
                continue;
            }

            // re-fetch remote task
            if(remoteConflict != null) {
                TaskProxy newTask = helper.refetchTask(remoteConflict);
                remoteTasks.remove(remoteConflict);
                remoteTasks.add(newTask);
            } else
                stats.remoteUpdatedTasks++;
        }

        // 4. REMOTE SYNC load remote information
        log.append("\n>> on astrid:\n");
        postUpdate(new ProgressUpdater(0, 1));
        for(TaskProxy remoteTask : remoteTasks) {
            if(remoteTask.name != null)
            	postUpdate(new ProgressLabelUpdater("Updating local " +
                		"tasks: " + remoteTask.name));
            else
            	postUpdate(new ProgressLabelUpdater("Updating local tasks"));
            SyncMapping mapping = null;
            TaskModelForSync task = null;

            // if it's new, create a new task model
            if(!data.remoteIdToSyncMapping.containsKey(remoteTask.getRemoteId())) {
                // if it's new & deleted, forget about it
                if(remoteTask.isDeleted()) {
                    continue;
                }

                task = taskController.searchForTaskForSync(remoteTask.name);
                if(task == null) {
                    task = new TaskModelForSync();
                    setupTaskDefaults(context, task);
                    log.append("added " + remoteTask.name + "\n");
                } else {
                    mapping = data.localIdToSyncMapping.get(task.getTaskIdentifier());
                    log.append("merged " + remoteTask.name + "\n");
                }
            } else {
                mapping = data.remoteIdToSyncMapping.get(remoteTask.getRemoteId());
                if(remoteTask.isDeleted()) {
                    taskController.deleteTask(mapping.getTask());
                    syncController.deleteSyncMapping(mapping);
                    log.append("deleted " + remoteTask.name + "\n");
                    stats.localDeletedTasks++;
                    continue;
                }

                log.append("updated '" + remoteTask.name + "'\n");
                task = taskController.fetchTaskForSync(
                        mapping.getTask());
            }

            // save the data
            remoteTask.writeToTaskModel(task);
            taskController.saveTask(task);

            // save tags
            if(remoteTask.tags != null) {
                LinkedList<TagIdentifier> taskTags = tagController.getTaskTags(task.getTaskIdentifier());
                HashSet<TagIdentifier> tagsToAdd = new HashSet<TagIdentifier>();
                for(String tag : remoteTask.tags) {
                    String tagLower = tag.toLowerCase();
                    if(!data.tagsByLCName.containsKey(tagLower)) {
                        TagIdentifier tagId = tagController.createTag(tag);
                        data.tagsByLCName.put(tagLower, tagId);
                        tagsToAdd.add(tagId);
                    } else
                        tagsToAdd.add(data.tagsByLCName.get(tagLower));
                }

                HashSet<TagIdentifier> tagsToDelete = new HashSet<TagIdentifier>(taskTags);
                tagsToDelete.removeAll(tagsToAdd);
                tagsToAdd.removeAll(taskTags);

                for(TagIdentifier tagId : tagsToDelete)
                    tagController.removeTag(task.getTaskIdentifier(), tagId);
                for(TagIdentifier tagId : tagsToAdd)
                    tagController.addTag(task.getTaskIdentifier(), tagId);
            }
            stats.localUpdatedTasks++;

            // try looking for this task if it doesn't already have a mapping
            if(mapping == null) {
                mapping = data.localIdToSyncMapping.get(task.getTaskIdentifier());
                if(mapping == null) {
                    try {
                        mapping = new SyncMapping(task.getTaskIdentifier(), remoteTask);
                        syncController.saveSyncMapping(mapping);
                        data.localIdToSyncMapping.put(task.getTaskIdentifier(),
                                mapping);
                    } catch (Exception e) {
                        // unique violation: ignore - it'll get merged later
                        Log.e("astrid-sync", "Exception creating mapping", e);
                    }
                }
                stats.localCreatedTasks++;
            }

            Notifications.updateAlarm(context, taskController, alertController,
                    task);
            postUpdate(new ProgressUpdater(stats.localUpdatedTasks,
                    remoteTasks.size()));
        }
        stats.localUpdatedTasks -= stats.localCreatedTasks;

        syncController.clearUpdatedTaskList(getId());
        postUpdate(new Runnable() {
            public void run() {
                stats.showDialog(context, log.toString());
            }
        });
    }

    /** Set up defaults from preferences for this task */
    private void setupTaskDefaults(Context context, TaskModelForSync task) {
        Integer reminder = Preferences.getDefaultReminder(context);
        if(reminder != null)
            task.setNotificationIntervalSeconds(24*3600*reminder);
    }

    // --- helper classes

    /** data structure builder */
    class SyncData {
        HashSet<SyncMapping> mappings;
        HashSet<TaskIdentifier> activeTasks;
        HashSet<TaskIdentifier> allTasks;

        HashMap<String, SyncMapping> remoteIdToSyncMapping;
        HashMap<TaskIdentifier, SyncMapping> localIdToSyncMapping;

        HashSet<SyncMapping> localChanges;
        HashSet<TaskIdentifier> mappedTasks;
        HashMap<TaskIdentifier, TaskProxy> remoteChangeMap;
        HashMap<String, TaskProxy> newRemoteTasks;

        HashMap<TagIdentifier, TagModelForView> tags;
        HashMap<String, TagIdentifier> tagsByLCName;

        HashSet<TaskIdentifier> newlyCreatedTasks;
        HashSet<TaskIdentifier> deletedTasks;

        public SyncData(Context context, LinkedList<TaskProxy> remoteTasks) {
            // 1. get data out of the database
            mappings = synchronizer.getSyncController(context).getSyncMapping(getId());
            activeTasks = synchronizer.getTaskController(context).getActiveTaskIdentifiers();
            allTasks = synchronizer.getTaskController(context).getAllTaskIdentifiers();
            tags = synchronizer.getTagController(context).getAllTagsAsMap();

            //  2. build helper data structures
            remoteIdToSyncMapping = new HashMap<String, SyncMapping>();
            localIdToSyncMapping = new HashMap<TaskIdentifier, SyncMapping>();
            localChanges = new HashSet<SyncMapping>();
            mappedTasks = new HashSet<TaskIdentifier>();
            for(SyncMapping mapping : mappings) {
                if(mapping.isUpdated())
                    localChanges.add(mapping);
                remoteIdToSyncMapping.put(mapping.getRemoteId(), mapping);
                localIdToSyncMapping.put(mapping.getTask(), mapping);
                mappedTasks.add(mapping.getTask());
            }
            tagsByLCName = new HashMap<String, TagIdentifier>();
            for(TagModelForView tag : tags.values())
                tagsByLCName.put(tag.getName().toLowerCase(), tag.getTagIdentifier());

            // 3. build map of remote tasks
            remoteChangeMap = new HashMap<TaskIdentifier, TaskProxy>();
            newRemoteTasks = new HashMap<String, TaskProxy>();
            for(TaskProxy remoteTask : remoteTasks) {
                if(remoteIdToSyncMapping.containsKey(remoteTask.getRemoteId())) {
                    SyncMapping mapping = remoteIdToSyncMapping.get(remoteTask.getRemoteId());
                    remoteChangeMap.put(mapping.getTask(), remoteTask);
                } else if(remoteTask.name != null){
                    newRemoteTasks.put(remoteTask.name, remoteTask);
                }
            }

            // 4. build data structures of things to do
            newlyCreatedTasks = new HashSet<TaskIdentifier>(activeTasks);
            newlyCreatedTasks.removeAll(mappedTasks);
            deletedTasks = new HashSet<TaskIdentifier>(mappedTasks);
            deletedTasks.removeAll(allTasks);
        }
    }


    /** statistics tracking and displaying */
    protected class SyncStats {
        int localCreatedTasks = 0;
        int localUpdatedTasks = 0;
        int localDeletedTasks = 0;

        int mergedTasks = 0;

        int remoteCreatedTasks = 0;
        int remoteUpdatedTasks = 0;
        int remoteDeletedTasks = 0;

        /** Display a dialog with statistics */
        public void showDialog(final Context context, String log) {
            progressDialog.hide();
            Resources r = context.getResources();

            if(Preferences.shouldSuppressSyncDialogs(context)) {
                return;
            }

            Dialog.OnClickListener finishListener = null;

            // nothing updated
            if(localCreatedTasks + localUpdatedTasks + localDeletedTasks +
                    mergedTasks + remoteCreatedTasks + remoteDeletedTasks +
                    remoteUpdatedTasks == 0) {
                if(!isBackgroundService())
                    DialogUtilities.okDialog(context, "Sync: Up to date!", finishListener);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(r.getString(R.string.sync_result_title, getName()));
            sb.append("\n\n");
            sb.append(log).append("\n");
            if(localCreatedTasks + localUpdatedTasks + localDeletedTasks > 0)
                sb.append(r.getString(R.string.sync_result_local)).append("\n");
            if(localCreatedTasks > 0)
            	sb.append(r.getString(R.string.sync_result_created, localCreatedTasks)).append("\n");
            if(localUpdatedTasks > 0)
            	sb.append(r.getString(R.string.sync_result_updated, localUpdatedTasks)).append("\n");
            if(localDeletedTasks > 0)
            	sb.append(r.getString(R.string.sync_result_deleted, localDeletedTasks)).append("\n");

            if(mergedTasks > 0)
            	sb.append("\n").append(r.getString(R.string.sync_result_merged, mergedTasks)).append("\n");
            sb.append("\n");

            if(remoteCreatedTasks + remoteDeletedTasks + remoteUpdatedTasks > 0)
            	sb.append(r.getString(R.string.sync_result_remote)).append("\n");
            if(remoteCreatedTasks > 0)
            	sb.append(r.getString(R.string.sync_result_created, remoteCreatedTasks)).append("\n");
            if(remoteUpdatedTasks > 0)
            	sb.append(r.getString(R.string.sync_result_updated, remoteUpdatedTasks)).append("\n");
            if(remoteDeletedTasks > 0)
            	sb.append(r.getString(R.string.sync_result_deleted, remoteDeletedTasks)).append("\n");

            sb.append("\n");

            DialogUtilities.okDialog(context, sb.toString(), finishListener);
        }
    }

    protected class ProgressUpdater implements Runnable {
        int step, outOf;
        public ProgressUpdater(int step, int outOf) {
            this.step = step;
            this.outOf = outOf;
        }
        public void run() {
        	if(!isBackgroundService())
        		progressDialog.setProgress(100*step/outOf);
        }
    }

    protected class ProgressLabelUpdater implements Runnable {
        String label;
        public ProgressLabelUpdater(String label) {
            this.label = label;
        }
        public void run() {
        	if(isBackgroundService()) {
        		Log.i("astrid-sync", label);
        	} else {
	            if(!progressDialog.isShowing())
	                progressDialog.show();
	            progressDialog.setMessage(label);
        	}
        }
    }
}
