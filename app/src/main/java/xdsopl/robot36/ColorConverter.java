/*
Color converter

Copyright 2024 Ahmet Inan <xdsopl@gmail.com>
*/

package xdsopl.robot36;

public final class ColorConverter {

	private static int clamp(int value) {
		return Math.min(Math.max(value, 0), 255);
	}

	private static float clamp(float value) {
		return Math.min(Math.max(value, 0), 1);
	}

	private static int float2int(float level) {
		int intensity = Math.round(255 * level);
		return clamp(intensity);
	}

	private static int compress(float level) {
		float compressed = (float) Math.sqrt(clamp(level));
		return float2int(compressed);
	}

	private static int YUV2RGB(int Y, int U, int V) {
		Y -= 16;
		U -= 128;
		V -= 128;
		int R = clamp((298 * Y + 409 * V + 128) >> 8);
		int G = clamp((298 * Y - 100 * U - 208 * V + 128) >> 8);
		int B = clamp((298 * Y + 516 * U + 128) >> 8);
		return 0xff000000 | (R << 16) | (G << 8) | B;
	}

	public static int GRAY(float level) {
		return 0xff000000 | 0x00010101 * compress(level);
	}

	public static int RGB(float red, float green, float blue) {
		return 0xff000000 | (float2int(red) << 16) | (float2int(green) << 8) | float2int(blue);
	}

	public static int YUV2RGB(float Y, float U, float V) {
		return YUV2RGB(float2int(Y), float2int(U), float2int(V));
	}

	public static int YUV2RGB(int YUV) {
		return YUV2RGB((YUV & 0x00ff0000) >> 16, (YUV & 0x0000ff00) >> 8, YUV & 0x000000ff);
	}
}
