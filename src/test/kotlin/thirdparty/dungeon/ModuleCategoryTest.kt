package thirdparty.dungeon

import cop.module.Category
import cop.module.Module
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleCategoryTest {
    @Test
    fun `foreign package names cannot impersonate built-in categories`() {
        val module = object : Module("Third-party dungeon helper") {}

        assertEquals(Category.ADDON, module.category)
    }

    @Test
    fun `addons can explicitly choose a category and subgroup`() {
        val module = object : Module(
            name = "Grouped addon",
            explicitCategory = Category.DUNGEON,
            explicitSubCategory = "example",
        ) {}

        assertEquals(Category.DUNGEON, module.category)
        assertEquals("example", module.subCategory)
    }
}
