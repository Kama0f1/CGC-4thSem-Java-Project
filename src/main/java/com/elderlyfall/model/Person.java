// src/main/java/com/elderlyfall/model/Person.java
package com.elderlyfall.model;

/**
 * Represents a detected person bounding box from the HOG detector.
 */
public class Person {

    private final int id;
    private int x, y, width, height;

    public Person(int id, int x, int y, int width, int height) {
        this.id = id; this.x = x; this.y = y;
        this.width = width; this.height = height;
    }

    /**
     * Returns true when the bounding box is significantly wider than tall —
     * i.e. the person is horizontal / on the floor.
     * The ratio threshold is configurable via fall.ratio in .env
     */
    public boolean isOnFloor(double fallRatio) {
        if (height <= 0) return false;
        return ((double) width / height) >= fallRatio;
    }

    public int getId()     { return id; }
    public int getX()      { return x; }
    public int getY()      { return y; }
    public int getWidth()  { return width; }
    public int getHeight() { return height; }
    public void setX(int x)          { this.x = x; }
    public void setY(int y)          { this.y = y; }
    public void setWidth(int w)      { this.width = w; }
    public void setHeight(int h)     { this.height = h; }

    @Override
    public String toString() {
        return String.format("Person{id=%d, x=%d, y=%d, w=%d, h=%d, ratio=%.2f}",
                id, x, y, width, height, height > 0 ? (double) width / height : 0);
    }
}
