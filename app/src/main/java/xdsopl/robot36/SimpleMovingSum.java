/*
Simple Moving Sum

Copyright 2024 Ahmet Inan <xdsopl@gmail.com>
*/

package xdsopl.robot36;

public class SimpleMovingSum {
	private final float[] tree;
	private int leaf;
	public final int length;

	public SimpleMovingSum(int length) {
		this.length = length;
		this.tree = new float[2 * length];
		this.leaf = length;
	}

	public void add(float input) {
		tree[leaf] = input;
		for (int child = leaf, parent = leaf / 2; parent > 0; child = parent, parent /= 2)
			tree[parent] = tree[child] + tree[child ^ 1];
		if (++leaf >= tree.length)
			leaf = length;
	}

	public float sum() {
		return tree[1];
	}

	public float sum(float input) {
		add(input);
		return sum();
	}
}

