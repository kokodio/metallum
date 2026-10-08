package com.metallum.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public record MTLClearColor(double red, double green, double blue, double alpha) {
}
