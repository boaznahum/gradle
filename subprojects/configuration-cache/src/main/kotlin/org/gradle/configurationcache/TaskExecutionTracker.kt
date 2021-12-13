/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache

import org.gradle.api.Task
import org.gradle.api.execution.TaskActionListener
import org.gradle.internal.operations.BuildOperationAncestryTracker
import org.gradle.internal.operations.BuildOperationRef
import org.gradle.internal.operations.CurrentBuildOperationRef
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.service.scopes.EventScope
import org.gradle.internal.service.scopes.Scopes
import java.util.Objects


/**
 * Tracks task execution. Can be used to find when an included build runs some forbidden action as part of the task execution, where it should be allowed.
 */
@EventScope(Scopes.BuildTree::class)
interface TaskExecutionTracker {
    fun isCurrentThreadExecutingTaskBuildOperation(): Boolean
}


class DefaultTaskExecutionTracker(
    private val buildOperationAncestryTracker: BuildOperationAncestryTracker,
) : TaskExecutionTracker, TaskActionListener {
    private
    val currentBuildOperationRef = CurrentBuildOperationRef.instance()

    private
    val currentRunningTaskOperations = HashSet<BuildOperationRef>()

    override fun beforeActions(task: Task) {
        currentBuildOperationRef.get()?.let { currentRunningTaskOperations.add(it) }
    }

    override fun afterActions(task: Task) {
        currentBuildOperationRef.get()?.let { currentRunningTaskOperations.remove(it) }
    }

    override fun isCurrentThreadExecutingTaskBuildOperation(): Boolean {
        val currentBuildOperationId = currentBuildOperationRef.id
        val taskAncestorId = buildOperationAncestryTracker.findClosestMatchingAncestor(currentBuildOperationId, ::isOperationWithIdRunningTaskNow)
        return taskAncestorId.isPresent
    }

    private
    fun isOperationWithIdRunningTaskNow(id: OperationIdentifier): Boolean {
        return currentRunningTaskOperations.find { taskOperationRef -> Objects.equals(id, taskOperationRef.id) } != null
    }
}
