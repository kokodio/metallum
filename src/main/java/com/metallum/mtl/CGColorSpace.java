package com.metallum.mtl;

import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;

@Environment(EnvType.CLIENT)
public final class CGColorSpace implements AutoCloseable {
    public static final MemorySegment kCGColorSpaceSRGB = ObjC.loadSymbol(ObjC.CORE_GRAPHICS, "kCGColorSpaceSRGB");
    public static final MemorySegment kCGColorSpaceDisplayP3 = ObjC.loadSymbol(ObjC.CORE_GRAPHICS, "kCGColorSpaceDisplayP3");

    private static final MethodHandle CREATE_WITH_NAME = ObjC.LINKER.downcallHandle(
            ObjC.CORE_GRAPHICS.findOrThrow("CGColorSpaceCreateWithName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle RELEASE = ObjC.LINKER.downcallHandle(
            ObjC.CORE_GRAPHICS.findOrThrow("CGColorSpaceRelease"), FunctionDescriptor.ofVoid(ADDRESS));

    private final MemorySegment handle;

    private CGColorSpace(final MemorySegment handle) {
        this.handle = handle;
    }

    public static CGColorSpace createWithName(final MemorySegment name) {
        try {
            return new CGColorSpace((MemorySegment) CREATE_WITH_NAME.invokeExact(name));
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    public MemorySegment handle() {
        return this.handle;
    }

    @Override
    public void close() {
        try {
            RELEASE.invokeExact(this.handle);
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }
}
