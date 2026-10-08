package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLTextureDescriptor extends NSObject {
    private static final MemorySegment CLS = ObjC.clazz("MTLTextureDescriptor");
    private static final Msg ALLOC = Msg.of("alloc", ADDRESS);
    private static final Msg INIT = Msg.of("init", ADDRESS);
    private static final Msg TEXTURE_BUFFER_DESCRIPTOR = Msg.of(
            "textureBufferDescriptorWithPixelFormat:width:resourceOptions:usage:",
            ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg SET_TEXTURE_TYPE = Msg.ofVoid("setTextureType:", JAVA_LONG);
    private static final Msg SET_PIXEL_FORMAT = Msg.ofVoid("setPixelFormat:", JAVA_LONG);
    private static final Msg SET_WIDTH = Msg.ofVoid("setWidth:", JAVA_LONG);
    private static final Msg SET_HEIGHT = Msg.ofVoid("setHeight:", JAVA_LONG);
    private static final Msg SET_MIPMAP_LEVEL_COUNT = Msg.ofVoid("setMipmapLevelCount:", JAVA_LONG);
    private static final Msg SET_ARRAY_LENGTH = Msg.ofVoid("setArrayLength:", JAVA_LONG);
    private static final Msg SET_USAGE = Msg.ofVoid("setUsage:", JAVA_LONG);
    private static final Msg SET_STORAGE_MODE = Msg.ofVoid("setStorageMode:", JAVA_LONG);
    private static final Msg SET_HAZARD_TRACKING_MODE = Msg.ofVoid("setHazardTrackingMode:", JAVA_LONG);

    private MTLTextureDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public static MTLTextureDescriptor textureBufferDescriptor(final MTLPixelFormat pixelFormat, final long width, final long resourceOptions, final long usage) {
        return new MTLTextureDescriptor(TEXTURE_BUFFER_DESCRIPTOR.sendPtr(CLS, pixelFormat.value, width, resourceOptions, usage));
    }

    public static MTLTextureDescriptor alloc() {
        return new MTLTextureDescriptor(ALLOC.sendPtr(CLS));
    }

    public MTLTextureDescriptor init() {
        this.handle = INIT.sendPtr(this.handle);
        return this;
    }


    public void setTextureType(final MTLTextureType textureType) {
        SET_TEXTURE_TYPE.send(this.handle, textureType.value);
    }

    public void setPixelFormat(final MTLPixelFormat pixelFormat) {
        SET_PIXEL_FORMAT.send(this.handle, pixelFormat.value);
    }

    public void setWidth(final long width) {
        SET_WIDTH.send(this.handle, width);
    }

    public void setHeight(final long height) {
        SET_HEIGHT.send(this.handle, height);
    }

    public void setMipmapLevelCount(final long mipmapLevelCount) {
        SET_MIPMAP_LEVEL_COUNT.send(this.handle, mipmapLevelCount);
    }

    public void setArrayLength(final long arrayLength) {
        SET_ARRAY_LENGTH.send(this.handle, arrayLength);
    }

    public void setUsage(final long usage) {
        SET_USAGE.send(this.handle, usage);
    }

    public void setStorageMode(final MTLStorageMode storageMode) {
        SET_STORAGE_MODE.send(this.handle, storageMode.value);
    }

    public void setHazardTrackingMode(final MTLHazardTrackingMode hazardTrackingMode) {
        SET_HAZARD_TRACKING_MODE.send(this.handle, hazardTrackingMode.value);
    }
}
