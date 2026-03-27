package net.shasankp000.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityTickInvoker {
    @Invoker("baseTick")
    void invokeBaseTick();
}

