package com.metallum.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public record MTLSize(long width, long height, long depth) {
    MemorySegment on(final MemoryStack stack) {
        MemorySegment struct = MemorySegment.ofAddress(stack.nmalloc(8, 24)).reinterpret(24);
        struct.set(JAVA_LONG, 0, this.width);
        struct.set(JAVA_LONG, 8, this.height);
        struct.set(JAVA_LONG, 16, this.depth);
        return struct;
    }
}
