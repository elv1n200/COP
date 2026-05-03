package cop.module.impl.player

import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf

/**
 * Tweaks for the vanilla third-person camera:
 *
 *  - **Clip** — disables the wall-raycast that pushes the camera back into the
 *    player when there's a wall behind. Camera stays at the requested distance.
 *  - **Custom distance** — overrides Mojang's hardcoded 4-block 3rd-person
 *    distance with anything between 3 and 15 blocks.
 *
 * Both work by short-circuiting `Camera.getMaxZoom` (see CameraMixin).
 *
 * @author elvin
 */
object CameraHelper : Module(
    "Camera Helper",
    desc = "No-clip + custom distance for the third-person camera.",
) {
    @JvmStatic val noClip by switch(
        "Camera clip", false,
        desc = "Stop the camera from pushing back into the player when there's a wall behind it.",
    )

    @JvmStatic val useCustomDistance by switch(
        "Custom distance", false,
        desc = "Override the default 4-block third-person distance.",
    )

    @JvmStatic val customDistance by slider(
        "Distance", 4.0f, 3.0f, 15.0f, 0.5f, unit = "blocks",
        desc = "Distance from the player when in third-person view.",
    ).childOf(::useCustomDistance)
}
