// Stonecutter root controller. Selects the active MC version for IDE/dev runs
// and declares source-preprocessor parameters that apply across every versioned
// subproject. The `build.gradle.kts` next to this file runs once per version.
//
// IDE: switch the active version via `./gradlew "Set active version to ..."`
// or by editing the line below and reloading.

plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.10"

stonecutter parameters {
    // Constants the source preprocessor can reference inside `//?` comments.
    swaps["mod_id"] = "\"" + project.property("mod_id") + "\""
    swaps["mod_version"] = "\"" + project.property("mod_version") + "\""
    swaps["minecraft"] = "\"" + node.metadata.version + "\""

    // String-level rewrites — applied to `.kt`/`.java` source before compile.
    // 1.21.11 mojmap renamed `ResourceLocation` to `Identifier` (matching the
    // yarn naming Mojang adopted), and shuffled several class packages around.
    // These globals catch the bulk of the simple rename/move cases.
    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
            // Package moves — class names unchanged, only the import path differs.
            replace("net.minecraft.Util",                                       "net.minecraft.util.Util")
            replace("net.minecraft.world.entity.monster.CaveSpider",            "net.minecraft.world.entity.monster.spider.CaveSpider")
            replace("net.minecraft.world.entity.monster.Spider",                "net.minecraft.world.entity.monster.spider.Spider")
            replace("net.minecraft.world.entity.monster.Zombie",                "net.minecraft.world.entity.monster.zombie.Zombie")
            replace("net.minecraft.world.entity.projectile.AbstractArrow",      "net.minecraft.world.entity.projectile.arrow.AbstractArrow")
            replace("net.minecraft.client.renderer.RenderType",                 "net.minecraft.client.renderer.rendertype.RenderType")
        }
    }
}

// Root-level aggregator — builds every versioned subproject's `buildAndCollect`
// task. Stonecutter 0.8 doesn't ship a built-in `chiseledBuild` task, so we
// register our own equivalent here.
tasks.register("buildAll") {
    group = "build"
    description = "Builds every versioned subproject and collects the jars in ./dist/."
    dependsOn(stonecutter.tree.nodes.map { ":${it.metadata.version}:buildAndCollect" })
}
