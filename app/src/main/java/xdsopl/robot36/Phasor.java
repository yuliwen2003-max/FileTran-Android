/*
Numerically controlled oscillator

Copyright 2024 Ahmet Inan <xdsopl@gmail.com>
*/

package xdsopl.robot36;

public class Phasor {
	private final Complex value;
	private final Complex delta;
	Phasor(double freq, double rate) {
		value = new Complex(1, 0);
		double omega = 2 * Math.PI * freq / rate;
		delta = new Complex((float) Math.cos(omega), (float) Math.sin(omega));
	}
	Complex rotate() {
		return value.div(value.mul(delta).abs());
	}
}
