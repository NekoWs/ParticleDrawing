package work.nekow.particledrawing.mixin.sodium;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Sodium 兼容 mixin 的开关。
 *
 * Sodium 是可选的第三方模组：没装时整组 mixin 都不应用；装了但类名对不上（版本差异）时也跳过，
 * 避免把游戏拖进“找不到注入目标”的崩溃。
 */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {
    private static final String SODIUM_ID = "sodium";
    private static volatile boolean announced;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 只看目标类在不在：正式环境里模组列表在 mixin 准备阶段可能还没就绪，
        // 用模组 id 判断会让整组兼容静默失效（开发环境反而正常）。
        boolean present = targetPresent(targetClassName);
        if (!announced) {
            announced = true;
            org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").info(
                "[PD-compat] Sodium mixins {} (target {})", present ? "enabled" : "skipped", targetClassName);
        }
        return present;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean sodiumLoaded() {
        try {
            var loader = FMLLoader.getCurrentOrNull();
            var loading = loader != null ? loader.getLoadingModList() : null;
            if (loading != null && loading.getModFileById(SODIUM_ID) != null) return true;
        } catch (Throwable ignored) {
            // 早期阶段拿不到加载列表时退回已加载模组表。
        }
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded(SODIUM_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean targetPresent(String targetClassName) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = SodiumMixinPlugin.class.getClassLoader();
        return loader != null && loader.getResource(targetClassName.replace('.', '/') + ".class") != null;
    }
}

