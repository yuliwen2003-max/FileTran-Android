/*
Exponential Moving Average

Copyright 2024 Ahmet Inan <xdsopl@gmail.com>
*/

package xdsopl.robot36;

@SuppressWarnings("unused")
public class ExponentialMovingAverage {
	private float alpha;
	private float prev;

	ExponentialMovingAverage() {
		this.alpha = 1;
	}

	public float avg(float input) {
		return prev = prev * (1 - alpha) + alpha * input;
	}

	public void alpha(double alpha) {
		this.alpha = (float) alpha;
	}

	public void alpha(double alpha, int order) {
		alpha(Math.pow(alpha, 1.0 / order));
	}

	public void cutoff(double freq, double rate, int order) {
		double x = Math.cos(2 * Math.PI * freq / rate);
		alpha(x - 1 + Math.sqrt(x * (x - 4) + 3), order);
	}

	public void cutoff(double freq, double rate) {
		cutoff(freq, rate, 1);
	}

	public void reset() {
		prev = 0;
	}
}
