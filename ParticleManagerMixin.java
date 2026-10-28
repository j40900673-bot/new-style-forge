package dev.styleforge.mixin;

import dev.styleforge.style.StyleManager;
import net.minecraft.block.BlockState;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Schaltet die Partikel beim Abbauen und Zerstoeren von Bloecken ab, wenn der Schalter in den Extras aus ist.
 * require = 0: Passt eine Methode in einer Version nicht, wird sie einfach uebersprungen (kein Absturz).
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {

    /** Partikel, wenn ein Block zerstoert wird. */
    @Inject(
            method = "addBlockBreakParticles(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void styleforge$noBreakParticles(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!StyleManager.isBlockParticles()) {
            ci.cancel();
        }
    }

    /** Partikel, waehrend ein Block abgebaut wird. */
    @Inject(
            method = "addBlockBreakingParticles(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void styleforge$noBreakingParticles(BlockPos pos, Direction side, CallbackInfo ci) {
        if (!StyleManager.isBlockParticles()) {
            ci.cancel();
        }
    }
}
