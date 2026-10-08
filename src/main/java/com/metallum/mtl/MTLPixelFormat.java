package com.metallum.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLPixelFormat {
    Invalid(0L),
    A8Unorm(1L),
    R8Unorm(10L),
    R8Unorm_sRGB(11L),
    R8Snorm(12L),
    R8Uint(13L),
    R8Sint(14L),
    R16Unorm(20L),
    R16Snorm(22L),
    R16Uint(23L),
    R16Sint(24L),
    R16Float(25L),
    RG8Unorm(30L),
    RG8Unorm_sRGB(31L),
    RG8Snorm(32L),
    RG8Uint(33L),
    RG8Sint(34L),
    B5G6R5Unorm(40L),
    A1BGR5Unorm(41L),
    ABGR4Unorm(42L),
    BGR5A1Unorm(43L),
    R32Uint(53L),
    R32Sint(54L),
    R32Float(55L),
    RG16Unorm(60L),
    RG16Snorm(62L),
    RG16Uint(63L),
    RG16Sint(64L),
    RG16Float(65L),
    RGBA8Unorm(70L),
    RGBA8Unorm_sRGB(71L),
    RGBA8Snorm(72L),
    RGBA8Uint(73L),
    RGBA8Sint(74L),
    BGRA8Unorm(80L),
    BGRA8Unorm_sRGB(81L),
    RGB10A2Unorm(90L),
    RGB10A2Uint(91L),
    RG11B10Float(92L),
    RGB9E5Float(93L),
    BGR10A2Unorm(94L),
    BGR10_XR(554L),
    BGR10_XR_sRGB(555L),
    RG32Uint(103L),
    RG32Sint(104L),
    RG32Float(105L),
    RGBA16Unorm(110L),
    RGBA16Snorm(112L),
    RGBA16Uint(113L),
    RGBA16Sint(114L),
    RGBA16Float(115L),
    BGRA10_XR(552L),
    BGRA10_XR_sRGB(553L),
    RGBA32Uint(123L),
    RGBA32Sint(124L),
    RGBA32Float(125L),
    BC1_RGBA(130L),
    BC1_RGBA_sRGB(131L),
    BC2_RGBA(132L),
    BC2_RGBA_sRGB(133L),
    BC3_RGBA(134L),
    BC3_RGBA_sRGB(135L),
    BC4_RUnorm(140L),
    BC4_RSnorm(141L),
    BC5_RGUnorm(142L),
    BC5_RGSnorm(143L),
    BC6H_RGBFloat(150L),
    BC6H_RGBUfloat(151L),
    BC7_RGBAUnorm(152L),
    BC7_RGBAUnorm_sRGB(153L),
    PVRTC_RGB_2BPP(160L),
    PVRTC_RGB_2BPP_sRGB(161L),
    PVRTC_RGB_4BPP(162L),
    PVRTC_RGB_4BPP_sRGB(163L),
    PVRTC_RGBA_2BPP(164L),
    PVRTC_RGBA_2BPP_sRGB(165L),
    PVRTC_RGBA_4BPP(166L),
    PVRTC_RGBA_4BPP_sRGB(167L),
    EAC_R11Unorm(170L),
    EAC_R11Snorm(172L),
    EAC_RG11Unorm(174L),
    EAC_RG11Snorm(176L),
    EAC_RGBA8(178L),
    EAC_RGBA8_sRGB(179L),
    ETC2_RGB8(180L),
    ETC2_RGB8_sRGB(181L),
    ETC2_RGB8A1(182L),
    ETC2_RGB8A1_sRGB(183L),
    ASTC_4x4_sRGB(186L),
    ASTC_5x4_sRGB(187L),
    ASTC_5x5_sRGB(188L),
    ASTC_6x5_sRGB(189L),
    ASTC_6x6_sRGB(190L),
    ASTC_8x5_sRGB(192L),
    ASTC_8x6_sRGB(193L),
    ASTC_8x8_sRGB(194L),
    ASTC_10x5_sRGB(195L),
    ASTC_10x6_sRGB(196L),
    ASTC_10x8_sRGB(197L),
    ASTC_10x10_sRGB(198L),
    ASTC_12x10_sRGB(199L),
    ASTC_12x12_sRGB(200L),
    ASTC_4x4_LDR(204L),
    ASTC_5x4_LDR(205L),
    ASTC_5x5_LDR(206L),
    ASTC_6x5_LDR(207L),
    ASTC_6x6_LDR(208L),
    ASTC_8x5_LDR(210L),
    ASTC_8x6_LDR(211L),
    ASTC_8x8_LDR(212L),
    ASTC_10x5_LDR(213L),
    ASTC_10x6_LDR(214L),
    ASTC_10x8_LDR(215L),
    ASTC_10x10_LDR(216L),
    ASTC_12x10_LDR(217L),
    ASTC_12x12_LDR(218L),
    ASTC_4x4_HDR(222L),
    ASTC_5x4_HDR(223L),
    ASTC_5x5_HDR(224L),
    ASTC_6x5_HDR(225L),
    ASTC_6x6_HDR(226L),
    ASTC_8x5_HDR(228L),
    ASTC_8x6_HDR(229L),
    ASTC_8x8_HDR(230L),
    ASTC_10x5_HDR(231L),
    ASTC_10x6_HDR(232L),
    ASTC_10x8_HDR(233L),
    ASTC_10x10_HDR(234L),
    ASTC_12x10_HDR(235L),
    ASTC_12x12_HDR(236L),
    GBGR422(240L),
    BGRG422(241L),
    Depth16Unorm(250L),
    Depth32Float(252L),
    Stencil8(253L),
    Depth24Unorm_Stencil8(255L),
    Depth32Float_Stencil8(260L),
    X32_Stencil8(261L),
    X24_Stencil8(262L),
    Unspecialized(263L);

    public final long value;

    MTLPixelFormat(final long value) {
        this.value = value;
    }

    public static MTLPixelFormat from(final com.mojang.blaze3d.GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> R8Unorm;
            case R8_SNORM -> R8Snorm;
            case R8_UINT -> R8Uint;
            case R8_SINT -> R8Sint;
            case R16_UNORM -> R16Unorm;
            case R16_SNORM -> R16Snorm;
            case R16_UINT -> R16Uint;
            case R16_SINT -> R16Sint;
            case R16_FLOAT -> R16Float;
            case RG8_UNORM -> RG8Unorm;
            case RG8_SNORM -> RG8Snorm;
            case RG8_UINT -> RG8Uint;
            case RG8_SINT -> RG8Sint;
            case R32_UINT -> R32Uint;
            case R32_SINT -> R32Sint;
            case R32_FLOAT -> R32Float;
            case RG16_UNORM -> RG16Unorm;
            case RG16_SNORM -> RG16Snorm;
            case RG16_UINT -> RG16Uint;
            case RG16_SINT -> RG16Sint;
            case RG16_FLOAT -> RG16Float;
            case RGBA8_UNORM -> RGBA8Unorm;
            case RGBA8_SNORM -> RGBA8Snorm;
            case RGBA8_UINT -> RGBA8Uint;
            case RGBA8_SINT -> RGBA8Sint;
            case RGB10A2_UNORM -> RGB10A2Unorm;
            case RGB10A2_UINT -> RGB10A2Uint;
            case RG11B10_FLOAT -> RG11B10Float;
            case RG32_UINT -> RG32Uint;
            case RG32_SINT -> RG32Sint;
            case RG32_FLOAT -> RG32Float;
            case RGBA16_UNORM -> RGBA16Unorm;
            case RGBA16_SNORM -> RGBA16Snorm;
            case RGBA16_UINT -> RGBA16Uint;
            case RGBA16_SINT -> RGBA16Sint;
            case RGBA16_FLOAT -> RGBA16Float;
            case RGBA32_UINT -> RGBA32Uint;
            case RGBA32_SINT -> RGBA32Sint;
            case RGBA32_FLOAT -> RGBA32Float;
            case D16_UNORM -> Depth16Unorm;
            case D32_FLOAT -> Depth32Float;
            case S8_UINT -> Stencil8;
            case D24_UNORM_S8_UINT -> Depth24Unorm_Stencil8;
            case D32_FLOAT_S8_UINT -> Depth32Float_Stencil8;
            default -> throw new IllegalStateException("Unsupported Metal texel buffer format: " + format);
        };
    }
}
