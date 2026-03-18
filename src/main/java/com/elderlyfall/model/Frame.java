// src/main/java/com/elderlyfall/model/Frame.java
package com.elderlyfall.model;

import java.util.Collections;
import java.util.List;

/**
 * A single captured video frame.
 * Carries the raw blurred image as a byte array (BGR, for JavaFX rendering)
 * plus the list of persons detected in this frame.
 */
public class Frame {

    private final int          frameNumber;
    private final List<Person> persons;
    private final byte[]       imageData;   // BGR pixels, width × height × 3
    private final int          imageWidth;
    private final int          imageHeight;

    public Frame(int frameNumber, List<Person> persons,
                 byte[] imageData, int imageWidth, int imageHeight) {
        this.frameNumber = frameNumber;
        this.persons     = Collections.unmodifiableList(persons);
        this.imageData   = imageData;
        this.imageWidth  = imageWidth;
        this.imageHeight = imageHeight;
    }

    public int          getFrameNumber() { return frameNumber; }
    public List<Person> getPersons()     { return persons; }
    public byte[]       getImageData()   { return imageData; }
    public int          getImageWidth()  { return imageWidth; }
    public int          getImageHeight() { return imageHeight; }
}
