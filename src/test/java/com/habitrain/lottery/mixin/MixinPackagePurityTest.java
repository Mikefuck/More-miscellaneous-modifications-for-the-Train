package com.habitrain.lottery.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.lottery.card.RolePoolVisibility;
import com.habitrain.lottery.mixinplugin.HabiLotteryMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for {@code B-11}: managed Mixin packages must NEVER contain ordinary
 * helper or plugin classes, otherwise SpongePowered Mixin throws
 * {@code IllegalClassLoadError} at runtime when any code references them directly.
 */
class MixinPackagePurityTest {

    private static final String MIXIN_ANNOTATION_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

    @Test
    void serverAndClientMixinPackagesContainOnlyDeclaredMixinClasses() throws Exception {
        assertMixinConfigPure("/habitrain_lottery.mixins.json");
        assertMixinConfigPure("/habitrain_lottery.client.mixins.json");
    }

    @Test
    void rolePoolVisibilityLivesOutsideMixinPackageAndUsesPublicVisibilityApi() throws Exception {
        assertEquals("com.habitrain.lottery.card", RolePoolVisibility.class.getPackageName());
        assertTrue(IMixinConfigPlugin.class.isAssignableFrom(HabiLotteryMixinPlugin.class));
        assertEquals("com.habitrain.lottery.mixinplugin", HabiLotteryMixinPlugin.class.getPackageName());

        String resource = "/" + RolePoolVisibility.class.getName().replace('.', '/') + ".class";
        try (InputStream in = RolePoolVisibility.class.getResourceAsStream(resource)) {
            assertNotNull(in, "Missing compiled RolePoolVisibility.class");
            byte[] raw = in.readAllBytes();
            String asciiView = new String(raw, StandardCharsets.ISO_8859_1);
            assertFalse(asciiView.contains("SreRoleOverrideResolver"),
                    "RolePoolVisibility must not reference obsolete SreRoleOverrideResolver");

            ClassNode node = new ClassNode();
            new ClassReader(raw).accept(node, 0);
            boolean callsPublicApi = false;
            for (MethodNode method : node.methods) {
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode call
                            && "com/habitrain/core/api/role/v2/RoleVisibilityApi".equals(call.owner)
                            && "filterVisible".equals(call.name)) {
                        callsPublicApi = true;
                        break;
                    }
                }
            }
            assertTrue(callsPublicApi,
                    "RolePoolVisibility.filterVisible must delegate to RoleVisibilityApi.filterVisible");
        }
    }

    private static void assertMixinConfigPure(String configResource) throws Exception {
        JsonObject json;
        try (InputStream in = MixinPackagePurityTest.class.getResourceAsStream(configResource)) {
            assertNotNull(in, "Missing mixin config " + configResource);
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        String mixinPkg = json.get("package").getAsString();
        String plugin = json.has("plugin") ? json.get("plugin").getAsString() : "";

        assertFalse(plugin.startsWith(mixinPkg + "."),
                configResource + " plugin '" + plugin + "' must not reside inside managed mixin package " + mixinPkg);
        assertEquals(HabiLotteryMixinPlugin.class.getName(), plugin,
                configResource + " must reference migrated HabiLotteryMixinPlugin");

        Set<String> declared = new LinkedHashSet<>();
        for (String key : List.of("mixins", "client", "server")) {
            if (json.has(key) && json.get(key).isJsonArray()) {
                for (JsonElement el : json.getAsJsonArray(key)) {
                    declared.add(el.getAsString());
                }
            }
        }
        assertFalse(declared.isEmpty(), configResource + " declared no mixins");

        String pkgRelPath = mixinPkg.replace('.', '/');
        for (String relName : declared) {
            String classRes = "/" + pkgRelPath + "/" + relName.replace('.', '/') + ".class";
            assertHasMixinAnnotation(classRes, configResource);
        }

        // Scan every top-level .class directly in the managed package directory on classpath
        // (excluding test classes) and in src/main/java so stray helper classes are caught immediately.
        Enumeration<URL> urls = MixinPackagePurityTest.class.getClassLoader().getResources(pkgRelPath);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (!"file".equalsIgnoreCase(url.getProtocol())) {
                continue;
            }
            Path dir = Path.of(URI.create(url.toString()));
            if (!Files.isDirectory(dir) || isTestOutputDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                for (Path classFile : stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".class"))
                        .toList()) {
                    // For direct com/habitrain/lottery/mixin, skip client subpackage when checking server JSON
                    Path relPath = dir.relativize(classFile);
                    String relUnix = relPath.toString().replace('\\', '/');
                    if ("com.habitrain.lottery.mixin".equals(mixinPkg) && relUnix.startsWith("client/")) {
                        continue;
                    }
                    String fileName = classFile.getFileName().toString();
                    if ("package-info.class".equals(fileName)) {
                        continue;
                    }
                    assertFalse(fileName.contains("$"),
                            "Inner/synthetic class '" + classFile + "' found inside managed Mixin package "
                                    + mixinPkg + " — move inner classes and switch maps outside mixin package!");
                    String relName = relUnix.substring(0, relUnix.length() - ".class".length()).replace('/', '.');
                    assertHasMixinAnnotationBytes(Files.readAllBytes(classFile), classFile.toString());
                    assertTrue(declared.contains(relName),
                            "Class " + mixinPkg + "." + relName + " in " + configResource
                                    + " package is @Mixin-annotated but missing from JSON list");
                }
            }
        }

        Path sourceDir = Path.of("src", "main", "java").resolve(pkgRelPath);
        if (Files.isDirectory(sourceDir)) {
            try (Stream<Path> stream = Files.walk(sourceDir)) {
                for (Path javaFile : stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .toList()) {
                    Path relPath = sourceDir.relativize(javaFile);
                    String relUnix = relPath.toString().replace('\\', '/');
                    if ("com.habitrain.lottery.mixin".equals(mixinPkg) && relUnix.startsWith("client/")) {
                        continue;
                    }
                    String fileName = javaFile.getFileName().toString();
                    if ("package-info.java".equals(fileName)) {
                        continue;
                    }
                    String relName = relUnix.substring(0, relUnix.length() - ".java".length()).replace('/', '.');
                    assertTrue(declared.contains(relName),
                            "Non-declared Java source file '" + javaFile + "' found inside managed Mixin package "
                                    + mixinPkg + " — move helpers/plugins outside the mixin package!");
                }
            }
        }
    }

    private static boolean isTestOutputDirectory(Path dir) {
        for (Path part : dir) {
            String s = part.toString();
            if ("test".equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    private static void assertHasMixinAnnotation(String classResource, String ownerConfig) throws IOException {
        try (InputStream in = MixinPackagePurityTest.class.getResourceAsStream(classResource)) {
            assertNotNull(in, ownerConfig + " references missing class resource " + classResource);
            assertHasMixinAnnotationBytes(in.readAllBytes(), classResource);
        }
    }

    private static void assertHasMixinAnnotationBytes(byte[] bytes, String label) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean hasMixin = hasAnnotation(node.visibleAnnotations) || hasAnnotation(node.invisibleAnnotations);
        assertTrue(hasMixin, label + " lives inside a managed Mixin package without @Mixin!");
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode ann : annotations) {
            if (MIXIN_ANNOTATION_DESC.equals(ann.desc)) {
                return true;
            }
        }
        return false;
    }
}
