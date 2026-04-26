package cop.api.customtriggers.conditions

import cop.api.customtriggers.Abobable
import cop.api.customtriggers.TriggerContext
import cop.config.TypeNamed

sealed interface TriggerCondition : TypeNamed, Abobable {
    fun matches(ctx: TriggerContext): Boolean
}