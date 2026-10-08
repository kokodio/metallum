package com.metallum;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MetallumConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("metallum.json");
    public static final MetallumConfig INSTANCE = load();

    public boolean displayP3 = false;

    private MetallumConfig() {
    }

    private static MetallumConfig load() {
        try (Reader reader = Files.newBufferedReader(FILE)) {
            MetallumConfig config = GSON.fromJson(reader, MetallumConfig.class);
            return config != null ? config : new MetallumConfig();
        } catch (IOException | JsonParseException e) {
            return new MetallumConfig();
        }
    }

    public void save() {
        try {
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException e) {
            Metallum.LOGGER.warn("Failed to save {}", FILE, e);
        }
    }
}
