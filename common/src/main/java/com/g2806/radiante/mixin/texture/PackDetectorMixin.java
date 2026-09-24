package com.g2806.radiante.mixin.texture;

import com.g2806.radiante.client.bedrock.BedrockPackConverter;
import com.g2806.radiante.platform.RadiantePlatform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.repository.PackDetector;
import net.minecraft.world.level.validation.ForbiddenSymlinkInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lists Bedrock packs ({@code .mcpack}, a zip under another name) in the resource pack folder as packs of their own.
 * Minecraft only takes zips and folders; a .mcpack is handed the LabPBR conversion Radiante keeps for it, so it can be
 * picked like any other pack, under its own file name, with nothing extra left in the folder.
 */
@Mixin(PackDetector.class)
public abstract class PackDetectorMixin {

    @Inject(method = "detectPackResources", at = @At("HEAD"), cancellable = true)
    private void radiante$detectBedrockPack(Path content, List<ForbiddenSymlinkInfo> issues,
        CallbackInfoReturnable<Object> cir) {
        if (!BedrockPackConverter.isBedrockPack(content) || !Files.isRegularFile(content)
            || !isResourcePackFolder(content.getParent())) {
            return;
        }
        Path converted = BedrockPackConverter.converted(content);
        cir.setReturnValue(converted == null ? null : new FilePackResources.FileResourcesSupplier(converted));
    }

    /** Only the client's resource packs: a world's data pack folder has no use for textures. */
    private static boolean isResourcePackFolder(Path folder) {
        if (folder == null) {
            return false;
        }
        try {
            return Files.isSameFile(folder, RadiantePlatform.INSTANCE.gameDir().resolve("resourcepacks"));
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
