package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

@Environment(EnvType.CLIENT)
public final class MTLSamplerDescriptor extends NSObject {
    private static final MemorySegment CLS = ObjC.clazz("MTLSamplerDescriptor");
    private static final Msg ALLOC = Msg.of("alloc", ADDRESS);
    private static final Msg INIT = Msg.of("init", ADDRESS);
    private static final Msg SET_MIN_FILTER = Msg.ofVoid("setMinFilter:", JAVA_LONG);
    private static final Msg SET_MAG_FILTER = Msg.ofVoid("setMagFilter:", JAVA_LONG);
    private static final Msg SET_MIP_FILTER = Msg.ofVoid("setMipFilter:", JAVA_LONG);
    private static final Msg SET_S_ADDRESS_MODE = Msg.ofVoid("setSAddressMode:", JAVA_LONG);
    private static final Msg SET_T_ADDRESS_MODE = Msg.ofVoid("setTAddressMode:", JAVA_LONG);
    private static final Msg SET_MAX_ANISOTROPY = Msg.ofVoid("setMaxAnisotropy:", JAVA_LONG);
    private static final Msg SET_LOD_MIN_CLAMP = Msg.ofVoid("setLodMinClamp:", JAVA_FLOAT);
    private static final Msg SET_LOD_MAX_CLAMP = Msg.ofVoid("setLodMaxClamp:", JAVA_FLOAT);

    private MTLSamplerDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public static MTLSamplerDescriptor alloc() {
        return new MTLSamplerDescriptor(ALLOC.sendPtr(CLS));
    }

    public MTLSamplerDescriptor init() {
        this.handle = INIT.sendPtr(this.handle);
        return this;
    }


    public void setMinFilter(final MTLSamplerMinMagFilter minFilter) {
        SET_MIN_FILTER.send(this.handle, minFilter.value);
    }

    public void setMagFilter(final MTLSamplerMinMagFilter magFilter) {
        SET_MAG_FILTER.send(this.handle, magFilter.value);
    }

    public void setMipFilter(final MTLSamplerMipFilter mipFilter) {
        SET_MIP_FILTER.send(this.handle, mipFilter.value);
    }

    public void setSAddressMode(final MTLSamplerAddressMode sAddressMode) {
        SET_S_ADDRESS_MODE.send(this.handle, sAddressMode.value);
    }

    public void setTAddressMode(final MTLSamplerAddressMode tAddressMode) {
        SET_T_ADDRESS_MODE.send(this.handle, tAddressMode.value);
    }

    public void setMaxAnisotropy(final long maxAnisotropy) {
        SET_MAX_ANISOTROPY.send(this.handle, maxAnisotropy);
    }

    public void setLodMinClamp(final float lodMinClamp) {
        SET_LOD_MIN_CLAMP.send(this.handle, lodMinClamp);
    }

    public void setLodMaxClamp(final float lodMaxClamp) {
        SET_LOD_MAX_CLAMP.send(this.handle, lodMaxClamp);
    }
}
