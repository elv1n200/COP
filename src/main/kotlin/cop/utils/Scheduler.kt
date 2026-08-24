package cop.utils

import cop.CopMod.mc
import cop.api.events.TickEvent
import cop.api.events.core.EventBus
import kotlinx.coroutines.CompletableDeferred

object Scheduler {
    private val clientTasks = mutableListOf<Task>()
    private val serverTasks = mutableListOf<Task>()
    private val taskLock = Any()

    data class Task(
        var delay: Int,
        val repeat: Int = -1,
        val cb: (Task) -> Unit
    ) {
        fun cancel() {
            synchronized(taskLock) {
                clientTasks.remove(this)
                serverTasks.remove(this)
            }
        }
    }

    init {
        EventBus.on<TickEvent.End> {
            tick(clientTasks, server = false)
        }

        EventBus.on<TickEvent.Server> {
            tick(serverTasks, server = true)
        }
    }

    private fun tick(tasks: MutableList<Task>, server: Boolean) {
        val due = mutableListOf<Task>()
        synchronized(taskLock) {
            for (i in tasks.size - 1 downTo 0) {
                val task = tasks[i]

                if (--task.delay > 0) continue
                due.add(task)
                if (task.repeat >= 0) task.delay = task.repeat
                else tasks.removeAt(i)
            }
        }
        due.forEach { task ->
            if (server) task.cb(task) else mc.submit { task.cb(task) }
        }
    }

    @JvmOverloads
    fun scheduleTask(delay: Int = 0, server: Boolean = false, cb: (Task) -> Unit) {
        scheduleTaskHandle(delay, server, cb)
    }

    fun scheduleTaskHandle(delay: Int = 0, server: Boolean = false, cb: (Task) -> Unit): Task {
        val task = Task(delay, cb = cb)
        synchronized(taskLock) {
            (if (server) serverTasks else clientTasks).add(task)
        }
        return task
    }

    @JvmOverloads
    fun scheduleLoop(
        interval: Int = 1,
        server: Boolean = false,
        cb: (Task) -> Unit
    ): Task {
        val task = Task(interval, interval, cb)
        synchronized(taskLock) {
            (if (server) serverTasks else clientTasks).add(task)
        }
        return task
    }

    suspend fun wait(ticks: Int = 1, server: Boolean = false) {
        if (ticks <= 0) return

        val deferred = CompletableDeferred<Unit>()

        scheduleTask(ticks, server = server) {
            deferred.complete(Unit)
        }

        deferred.await()
    }
}
