package cop.api.commands.parsers

import cop.api.commands.internal.GreedyString
import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import java.lang.reflect.Type

object TypeParser {
    fun getBrigadierType(type: Type): ArgumentType<*> {
        return when (type) {
            Int::class.java, Int::class.javaObjectType -> IntegerArgumentType.integer()
            Double::class.java, Double::class.javaObjectType -> DoubleArgumentType.doubleArg()
            Float::class.java, Float::class.javaObjectType -> FloatArgumentType.floatArg()
            Boolean::class.java, Boolean::class.javaObjectType -> BoolArgumentType.bool()
            String::class.java -> StringArgumentType.string()
            GreedyString::class.java -> StringArgumentType.greedyString()
            else -> throw IllegalArgumentException("Unsupported command argument type: $type")
        }
    }

    fun getValue(context: CommandContext<FabricClientCommandSource>, name: String, type: Type): Any {
        return when (type) {
            Int::class.java, Int::class.javaObjectType -> IntegerArgumentType.getInteger(context, name)
            Double::class.java, Double::class.javaObjectType -> DoubleArgumentType.getDouble(context, name)
            Float::class.java, Float::class.javaObjectType -> FloatArgumentType.getFloat(context, name)
            Boolean::class.java, Boolean::class.javaObjectType -> BoolArgumentType.getBool(context, name)
            String::class.java -> StringArgumentType.getString(context, name)
            GreedyString::class.java -> GreedyString(StringArgumentType.getString(context, name))
            else -> throw IllegalArgumentException("Unsupported command argument type: $type")
        }
    }
}
