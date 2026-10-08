package com.metallum.objc;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;

public final class ObjC {
    public static final Linker LINKER = Linker.nativeLinker();

    private static final SymbolLookup RUNTIME = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
    public static final SymbolLookup FOUNDATION = SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", Arena.global());
    public static final SymbolLookup METAL = SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", Arena.global());
    public static final SymbolLookup QUARTZ_CORE = SymbolLookup.libraryLookup("/System/Library/Frameworks/QuartzCore.framework/QuartzCore", Arena.global());
    public static final SymbolLookup CORE_GRAPHICS = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", Arena.global());

    private static final MemorySegment MSG_SEND = RUNTIME.findOrThrow("objc_msgSend");
    private static final MethodHandle OBJC_GET_CLASS =
            LINKER.downcallHandle(RUNTIME.findOrThrow("objc_getClass"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SEL_REGISTER_NAME =
            LINKER.downcallHandle(RUNTIME.findOrThrow("sel_registerName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle POOL_PUSH =
            LINKER.downcallHandle(RUNTIME.findOrThrow("objc_autoreleasePoolPush"), FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle POOL_POP =
            LINKER.downcallHandle(RUNTIME.findOrThrow("objc_autoreleasePoolPop"), FunctionDescriptor.ofVoid(ADDRESS));

    private static final Map<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();

    private static final MethodHandle RETAIN = msgSendCritical(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RELEASE = msgSendCritical(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MemorySegment SEL_RETAIN = selector("retain");
    private static final MemorySegment SEL_RELEASE = selector("release");

    private static final MemorySegment NSSTRING = clazz("NSString");
    private static final MemorySegment SEL_ALLOC = selector("alloc");
    private static final MemorySegment SEL_INIT_WITH_UTF8 = selector("initWithUTF8String:");
    private static final MemorySegment SEL_UTF8_STRING = selector("UTF8String");
    private static final MethodHandle MSG_GET_PTR = msgSendCritical(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle MSG_PTR_ARG = msgSendCritical(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    private ObjC() {
    }

    public static MemorySegment retain(MemorySegment object) {
        try {
            return (MemorySegment) RETAIN.invokeExact(object, SEL_RETAIN);
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    public static void release(MemorySegment object) {
        try {
            RELEASE.invokeExact(object, SEL_RELEASE);
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    static MethodHandle msgSend(FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(MSG_SEND, descriptor);
    }

    static MethodHandle msgSendCritical(FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(MSG_SEND, descriptor, Linker.Option.critical(false));
    }

    public static MemorySegment nsString(String value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment alloc = (MemorySegment) MSG_GET_PTR.invokeExact(NSSTRING, SEL_ALLOC);
            return (MemorySegment) MSG_PTR_ARG.invokeExact(alloc, SEL_INIT_WITH_UTF8, arena.allocateFrom(value));
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    public static String javaString(MemorySegment nsString) {
        try {
            MemorySegment utf8 = (MemorySegment) MSG_GET_PTR.invokeExact(nsString, SEL_UTF8_STRING);
            return isNil(utf8) ? "" : utf8.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    public static boolean isNil(MemorySegment segment) {
        return segment == null || segment.address() == 0L;
    }

    public static java.nio.ByteBuffer byteBufferView(MemorySegment pointer, long byteSize) {
        if (isNil(pointer)) {
            throw new IllegalArgumentException("Cannot create a ByteBuffer view for a null native pointer");
        }
        if (byteSize < 0L) {
            throw new IllegalArgumentException("Byte size must be non-negative");
        }
        return MemorySegment.ofAddress(pointer.address()).reinterpret(byteSize).asByteBuffer();
    }

    public static MemorySegment orNil(MemorySegment segment) {
        return isNil(segment) ? MemorySegment.NULL : segment;
    }

    public static MemorySegment loadSymbol(SymbolLookup lookup, String name) {
        return lookup.findOrThrow(name).reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    }

    public static MemorySegment selector(String name) {
        return SELECTORS.computeIfAbsent(name, key -> {
            try (Arena arena = Arena.ofConfined()) {
                return (MemorySegment) SEL_REGISTER_NAME.invokeExact(arena.allocateFrom(key));
            } catch (Throwable throwable) {
                throw new AssertionError("sel_registerName failed for " + key, throwable);
            }
        });
    }

    public static MemorySegment clazz(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cls = (MemorySegment) OBJC_GET_CLASS.invokeExact(arena.allocateFrom(name));
            if (cls.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Objective-C class not found: " + name + " (framework not loaded?)");
            }
            return cls;
        } catch (Throwable throwable) {
            throw new AssertionError("objc_getClass failed for " + name, throwable);
        }
    }

    public static MemorySegment autoreleasePoolPush() {
        try {
            return (MemorySegment) POOL_PUSH.invokeExact();
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    public static void autoreleasePoolPop(MemorySegment pool) {
        try {
            POOL_POP.invokeExact(pool);
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }
}
