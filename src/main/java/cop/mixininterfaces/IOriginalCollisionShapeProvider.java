package cop.mixininterfaces;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

public interface IOriginalCollisionShapeProvider {
    VoxelShape cop$getOriginalCollisionShape(BlockState state);
}