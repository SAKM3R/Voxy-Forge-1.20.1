package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.distgen.DistantGenerationManager;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        var imports = Commands.literal("import")
                .then(Commands.literal("world")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(Commands.literal("bobby")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(Commands.literal("raw")
                        .then(Commands.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(Commands.literal("zip")
                        .then(Commands.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(Commands.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(Commands.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(Commands.literal("distant_horizons")
                    .then(Commands.argument("sqlDbPath", StringArgumentType.string())
                            .executes(VoxyCommands::importDistantHorizons)));
        }

        return Commands.literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(Commands.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(Commands.literal("distantgen")
                        .then(Commands.literal("status")
                                .executes(VoxyCommands::distantGenStatus))
                        .then(Commands.literal("pause")
                                .executes(ctx->distantGenSetPaused(ctx, true)))
                        .then(Commands.literal("resume")
                                .executes(ctx->distantGenSetPaused(ctx, false)))
                        .then(Commands.literal("reset")
                                .executes(VoxyCommands::distantGenReset)))
                .then(Commands.literal("colorfix")
                        .executes(VoxyCommands::toggleColorFix)
                        .then(Commands.literal("on").executes(ctx->setColorFix(ctx, true)))
                        .then(Commands.literal("off").executes(ctx->setColorFix(ctx, false))))
                .then(Commands.literal("dump")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx->dumpModels(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("debug")
                        .then(Commands.argument("mode", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 4))
                                .executes(ctx->setDebugMode(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "mode")))))
                .then(Commands.literal("curvefix")
                        .executes(VoxyCommands::toggleCurveFix)
                        .then(Commands.literal("on").executes(ctx->setCurveFix(ctx, true)))
                        .then(Commands.literal("off").executes(ctx->setCurveFix(ctx, false))))
                .then(imports);
    }

    private static int toggleColorFix(CommandContext<CommandSourceStack> ctx) {
        return setColorFix(ctx, !VoxyConfig.CONFIG.colorFix);
    }

    private static int setColorFix(CommandContext<CommandSourceStack> ctx, boolean on) {
        VoxyConfig.CONFIG.colorFix = on;
        ctx.getSource().sendSuccess(()->Component.translatable(on ? "voxy.cmd.colorfix.on" : "voxy.cmd.colorfix.off"), false);
        return 0;
    }

    private static int dumpModels(CommandContext<CommandSourceStack> ctx, String name) {
        var renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer == null) {
            ctx.getSource().sendFailure(Component.translatable("voxy.cmd.not_rendering"));
            return 1;
        }
        try {
            String where = renderer.dumpModels(name);
            ctx.getSource().sendSuccess(()->Component.translatable("voxy.cmd.dump.done", where), false);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.translatable("voxy.cmd.dump.failed", String.valueOf(e)));
        }
        return 0;
    }

    private static int setDebugMode(CommandContext<CommandSourceStack> ctx, int mode) {
        VoxyConfig.debugMode = mode;
        ctx.getSource().sendSuccess(()->Component.translatable("voxy.cmd.debug", mode, Component.translatable("voxy.cmd.debug.mode" + mode)), false);
        return 0;
    }

    private static int toggleCurveFix(CommandContext<CommandSourceStack> ctx) {
        return setCurveFix(ctx, !VoxyConfig.CONFIG.curveFix);
    }

    private static int setCurveFix(CommandContext<CommandSourceStack> ctx, boolean on) {
        VoxyConfig.CONFIG.curveFix = on;
        ctx.getSource().sendSuccess(()->Component.translatable(on ? "voxy.cmd.curvefix.on" : "voxy.cmd.curvefix.off"), false);
        return 0;
    }

    private static int distantGenStatus(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!VoxyConfig.CONFIG.distantGenEnabled) {
            src.sendSuccess(()->Component.translatable("voxy.cmd.distantgen.disabled"), false);
            return 0;
        }
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            src.sendSuccess(()->Component.translatable("voxy.cmd.distantgen.singleplayer_only"), false);
            return 0;
        }
        src.sendSuccess(()->Component.translatable("voxy.cmd.distantgen.status", Component.translatable(DistantGenerationManager.isPaused()?"voxy.cmd.distantgen.state.paused":"voxy.cmd.distantgen.state.running"), VoxyConfig.CONFIG.distantGenRadius), false);
        var lines = DistantGenerationManager.statusLines();
        if (lines.isEmpty()) {
            src.sendSuccess(()->Component.translatable("voxy.cmd.distantgen.none"), false);
        }
        for (var line : lines) {
            src.sendSuccess(()->line, false);
        }
        return 0;
    }

    private static int distantGenSetPaused(CommandContext<CommandSourceStack> ctx, boolean pause) {
        DistantGenerationManager.setPaused(pause);
        ctx.getSource().sendSuccess(()->Component.translatable(pause ? "voxy.cmd.distantgen.paused" : "voxy.cmd.distantgen.resumed"), false);
        return 0;
    }

    private static int distantGenReset(CommandContext<CommandSourceStack> ctx) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        var level = Minecraft.getInstance().level;
        if (server == null || level == null) {
            ctx.getSource().sendFailure(Component.translatable("voxy.cmd.distantgen.singleplayer_only"));
            return 1;
        }
        var dim = level.dimension();
        //Generator state is owned by the server thread
        server.execute(()->{
            var serverLevel = server.getLevel(dim);
            if (serverLevel != null) {
                DistantGenerationManager.resetLevel(serverLevel);
            }
        });
        ctx.getSource().sendSuccess(()->Component.translatable("voxy.cmd.distantgen.reset", dim.location().toString()), false);
        return 0;
    }

    private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) r.allChanged();
        return 0;
    }




    private static int importDistantHorizons(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null)return 1;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter))?0:1;
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }

    private static int importWorld(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) return 1;
            return fileBasedImporter(dimFile)?0:1;
            //We are in a world directory, so import the current dimension we are in
            /*
            for (var dim : new String[]{"overworld", "the_nether", "the_end"}) {//This is so annoying that you cant loop through all the dimensions
                var id = ResourceKey.create(Registries.DIMENSION, new ResourceLocation("minecraft", dim));
                var dimPath = DimensionType.getStorageFolder(id, file);
                dimPath = dimPath.resolve("region");
                var dimFile = dimPath.toFile();
                if (dimFile.isDirectory()) {//exists and is a directory
                    if (!fileBasedImporter(dimFile)) {
                        Logger.error("Failed to import dimension: " + id);
                    }
                }
            }*/
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile()) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<CommandSourceStack> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}