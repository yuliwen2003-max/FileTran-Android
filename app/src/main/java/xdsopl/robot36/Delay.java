/*
Digital delay line

Copyright 2024 Ahmet Inan <xdsopl@gmail.com>
*/

package xdsopl.robot36;

public class Delay {
	public final int length;
	private final float[] buf;
	private int pos;

	Delay(int length) {
		this.length = length;
		this.buf = new float[length];
		this.pos = 0;
	}

	float push(float input) {
		float tmp = buf[pos];
		buf[pos] = input;
		if (++pos >= length)
			pos = 0;
		return tmp;
	}
}
