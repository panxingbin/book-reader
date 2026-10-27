import com.intellij.ide.plugins.DataLoader;
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.ide.plugins.PathResolver;
import com.intellij.ide.plugins.PluginDescriptorLoader;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.plugins.RawPluginDescriptor;
import com.intellij.ide.plugins.ReadModuleContext;
import com.intellij.openapi.util.BuildNumber;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads the built plugin with the IntelliJ Platform's **own** descriptor loader —
 * the same code path the IDE runs at startup.
 *
 * This catches what a static XML check cannot: unknown extension point names,
 * classes that do not implement the required interface, malformed keyboard
 * shortcuts, unresolvable icons, bad `<depends>`, a wrong `since-build`.
 *
 * Run it through {@code tools/descriptor-check/check.ps1}, which assembles the
 * platform classpath for you.
 *
 * Usage: DescriptorCheck &lt;plugin.zip&gt;
 */
public class DescriptorCheck {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: DescriptorCheck <plugin.zip>");
            System.exit(2);
            return;
        }

        Path zip = Paths.get(args[0]);
        System.out.println("loading descriptor from: " + zip.toAbsolutePath());

        // The plugin uses neither x-includes nor module descriptors, so a resolver
        // that declines to resolve anything is enough.
        PathResolver resolver = new PathResolver() {
            @Override
            public boolean loadXIncludeReference(RawPluginDescriptor descriptor, ReadModuleContext context,
                                                 DataLoader loader, String path, String basePath) {
                return false;
            }

            @Override
            public RawPluginDescriptor resolvePath(ReadModuleContext context, DataLoader loader,
                                                   String path, RawPluginDescriptor descriptor) {
                return null;
            }

            @Override
            public RawPluginDescriptor resolveModuleFile(ReadModuleContext context, DataLoader loader,
                                                         String path, RawPluginDescriptor descriptor) {
                return null;
            }
        };

        IdeaPluginDescriptorImpl descriptor = PluginDescriptorLoader.loadDescriptorFromArtifact(
                zip, BuildNumber.fromString(System.getProperty("bookreader.buildNumber", "232")));

        if (descriptor == null) {
            descriptor = PluginDescriptorLoader.loadDescriptor(zip, false, resolver);
        }
        if (descriptor == null) {
            System.out.println("COULD NOT LOAD DESCRIPTOR");
            System.exit(1);
            return;
        }

        String name = descriptor.getName();
        StringBuilder codePoints = new StringBuilder();
        name.codePoints().forEach(cp -> codePoints.append(String.format("U+%04X ", cp)));

        System.out.println("---- descriptor ----");
        System.out.println("id            = " + descriptor.getPluginId());
        System.out.println("name          = " + name);
        System.out.println("name codepoints = " + codePoints.toString().trim());
        System.out.println("version       = " + descriptor.getVersion());
        System.out.println("sinceBuild    = " + descriptor.getSinceBuild());
        System.out.println("untilBuild    = " + descriptor.getUntilBuild());
        System.out.println("vendor        = " + descriptor.getVendor());
        System.out.println("dependencies  = " + descriptor.getDependencies());

        System.out.println("---- build compatibility ----");
        System.out.println("isCompatible  = " + PluginManagerCore.isCompatible(descriptor));

        System.out.println("DESCRIPTOR LOADED OK");
    }
}
