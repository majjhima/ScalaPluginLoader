package xyz.janboerman.scalaloader.example.java;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.configuration.serialization.SerializableAs;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static xyz.janboerman.scalaloader.example.java.ExamplePlugin.assertionsEnabled;

class ListSerializationTest {

    private final File saveFile;
    private final Logger logger;

    ListSerializationTest(ExamplePlugin plugin) {
        File dataFolder = plugin.getDataFolder();
        dataFolder.mkdirs();
        saveFile = new File(dataFolder, "array-serialization-test.yml");
        if (!saveFile.exists()) {
            try {
                saveFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        this.logger = plugin.getLogger();
    }

    void test() {
        logger.info("test deserialize(serialize(listserializable)).equals(listserializable)");
        ConfigurationSerialization.registerClass(ListSerializable.class, "ListSerializable");

        ListSerializable listSerializable = new ListSerializable(List.of(4L, 5L));
        YamlConfiguration yamlConfiguration = new YamlConfiguration();
        yamlConfiguration.set("listserializable", listSerializable);

        try {
            yamlConfiguration.save(saveFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

        yamlConfiguration = YamlConfiguration.loadConfiguration(saveFile);
        assert listSerializable.equals(yamlConfiguration.get("listserializable")) : "original listserializable does not equal deserialized listserializable";
        if (!assertionsEnabled()) {
            logger.info("test passed!");
        }
    }
}

@SerializableAs("ListSerializable")
public class ListSerializable implements ConfigurationSerializable {

    private final List<Long> longs;
    private final List<List<Float>[]> listOfArrayOfListOfFloat = List.of();

    ListSerializable() {
        longs = List.of(1L, 2L, 3L);
    }

    ListSerializable(List<Long> longs) {
        this.longs = longs;
    }

    @Override
    public Map<String, Object> serialize() {
        return Map.of("longs", longs.stream().map(longg -> longg.toString()).collect(Collectors.toList()));
    }

    public static ListSerializable deserialize(Map<String, Object> map) {
        return new ListSerializable(((List<String>) map.get("longs")).stream().map(Long::parseLong).collect(Collectors.toList()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ListSerializable that)) return false;

        return Objects.equals(longs, that.longs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(longs);
    }
}