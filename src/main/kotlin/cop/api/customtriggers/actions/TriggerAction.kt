package cop.api.customtriggers.actions

import cop.api.customtriggers.Abobable
import cop.api.customtriggers.TriggerContext
import cop.config.TypeNamed

sealed interface TriggerAction : TypeNamed, Abobable {
    fun execute(ctx: TriggerContext)
}