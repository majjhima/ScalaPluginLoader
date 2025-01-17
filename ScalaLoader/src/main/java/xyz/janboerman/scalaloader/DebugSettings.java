package xyz.janboerman.scalaloader;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import xyz.janboerman.scalaloader.compat.Compat;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class DebugSettings {

    private static final String FILE_NAME = "debug.yml";
    private static final String CLASS_NAMES = "class-names";
    private static final String FORMAT = "format";
    private static final String ANALYSIS = "analysis"; //value would be List<String>: the methods that need analyzing

    public static final String TEXTIFIED = "Textified";
    public static final String ASMIFIED = "ASMified";

    private final ScalaLoader scalaLoader;
    private File saveFile;

    //Synchronized because the ScalaPluginClassLoader is parallel capable! Some classes may be loaded in a different thread than the server's primary thread!
    private final Set<String> classNames = Collections.synchronizedSet(new LinkedHashSet<>());
    private String format = TEXTIFIED; private final Object formatLock = new Object();

    public DebugSettings(ScalaLoader scalaLoader) {
        this.scalaLoader = scalaLoader;
        this.saveFile = new File(scalaLoader.getDataFolder(), FILE_NAME);
        if (saveFile.exists()) try {
            load();
        } catch (IOException e) {
            scalaLoader.getLogger().log(Level.WARNING, "Could not load debug classes.", e);
        }
    }

    public Set<String> debugClassLoads() {
        return Collections.unmodifiableSet(classNames);
    }

    public String getFormat() {
        synchronized (formatLock) {
            return format;
        }
    }

    public boolean isDebuggingClassLoadOf(String className) {
        return classNames.contains(className);
    }

    public boolean debugClass(String className) {
        classNames.add(className);
        try {
            save();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean undebugClass(String className) {
        classNames.remove(className);
        try {
            save();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void load() throws IOException {
        if (saveFile != null && saveFile.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(saveFile);
            List<String> classNames = config.getStringList(CLASS_NAMES);
            this.classNames.clear();
            this.classNames.addAll(classNames);

            String format = config.getString(FORMAT, TEXTIFIED);
            synchronized (formatLock) {
                this.format = format;
            }
        }
    }

    private void save() throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.set(CLASS_NAMES, Compat.listCopy(classNames));
        config.set(FORMAT, getFormat());
        config.save(getSaveFile());
    }

    private File getSaveFile() throws IOException {
        if (!saveFile.exists()) saveFile.createNewFile();

        return saveFile;
    }


}
