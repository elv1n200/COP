package cop.api.abobaui.transforms.impl

import cop.api.abobaui.elements.Element
import cop.api.abobaui.transforms.Transform
import cop.utils.ui.rendering.NVGRenderer

class Alpha(override var amount: Float) : Transform.Mutable {
    override fun apply(element: Element) {
        if (element.ui.nvgPass) NVGRenderer.globalAlpha(amount)
    }

    /**
     * # Alpha.Animated
     *
     * This transformation changes the alpha of an element by an amount, which can be animated.
     */
    class Animated(
        from: Float,
        to: Float,
    ) : Transform.Animated(from, to) {
        override fun apply(element: Element) {
            if (element.ui.nvgPass) NVGRenderer.globalAlpha(get())
        }
    }
}