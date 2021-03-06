/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2021 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2021 The MITRE Corporation                                       *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/

package org.mitre.mpf.videooverlay;

import org.mitre.mpf.wfm.buffers.Markup;

import java.util.Objects;
import java.util.Optional;

/**
 * A bounding box is rectangle with an RGB color associated with it. Coordinates are
 * defined according to <a href="http://docs.oracle.com/javase/tutorial/2d/overview/coordinate.html">Java's 2D API Concepts</a>.
 */
public class BoundingBox {

    /**
     * The x-coordinate of the top-left corner of this bounding box on the given frame.
     */
    private final int x;
    public int getX() {
        return x;
    }

    /**
     * The y-coordinate of the top-left corner of this bounding box on the given frame.
     */
    private final int y;
    public int getY() {
        return y;
    }

    /**
     * The width of the bounding box.
     */
    private final int width;
    public int getWidth() {
        return width;
    }

    /**
     * The height of the bounding box.
     */
    private final int height;
    public int getHeight() {
        return height;
    }

    private final double rotationDegrees;
    public double getRotationDegrees() {
        return rotationDegrees;
    }

    private final boolean flip;
    public boolean getFlip() {
        return flip;
    }

    private final int red;
    public int getRed() {
        return red;
    }

    private final int green;
    public int getGreen() {
        return green;
    }

    private final int blue;
    public int getBlue() {
        return blue;
    }

    private final BoundingBoxSource source;
    public BoundingBoxSource getSource() {
        return source;
    }

    private final boolean moving;
    public boolean isMoving() {
        return moving;
    }

    private final boolean exemplar;
    public boolean isExemplar() {
        return exemplar;
    }

    private final Optional<String> label;
    public Optional<String> getLabel() {
        return label;
    }


    public BoundingBox(int x, int y, int width, int height, double rotationDegrees, boolean flip,
                       int red, int green, int blue, Optional<String> label) {
        this(x, y, width, height, rotationDegrees, flip, red, green, blue, BoundingBoxSource.DETECTION_ALGORITHM,
                true, false, label);
    }

    public BoundingBox(int x, int y, int width, int height, double rotationDegrees, boolean flip,
                       int red, int green, int blue, BoundingBoxSource source, boolean moving,
                       boolean exemplar, Optional<String> label) {
        if (red < 0 || red > 255) {
            throw new IllegalArgumentException("red must be in range [0,255]");
        }
        if (green < 0 || green > 255) {
            throw new IllegalArgumentException("green must be in range [0,255]");
        }
        if (blue < 0 || blue > 255) {
            throw new IllegalArgumentException("blue must be in range [0,255]");
        }

        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.rotationDegrees = rotationDegrees;
        this.flip = flip;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.source = source;
        this.moving = moving;
        this.exemplar = exemplar;
        this.label = label;
    }

    @Override
    public String toString() {
        String str = String.format("%s#<x=%d, y=%d, height=%d, width=%d, rotation=%f, color=(%d, %d, %d), source=%s," +
                        " moving=%b, exemplar=%b",
                getClass().getSimpleName(), x, y, height, width, rotationDegrees, red, green, blue, source,
                moving, exemplar);
        if (label.isPresent()) {
            str += ", label=\"" + label.get() + "\"";
        }
        str += ">";
        return str;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BoundingBox)) {
            return false;
        }
        BoundingBox casted = (BoundingBox) obj;
        return x == casted.x
                && y == casted.y
                && height == casted.height
                && width == casted.width
                && Double.compare(rotationDegrees, casted.rotationDegrees) == 0
                && flip == casted.flip
                && red == casted.red
                && green == casted.green
                && blue == casted.blue
                && source == casted.source
                && moving == casted.moving
                && exemplar == casted.exemplar
                && label.equals(casted.label);
    }

    /**
     * Uses mutable fields - do not modify an object which is a key in a map.
     */
    @Override
    public int hashCode() {
        return Objects.hash(x, y, height, width, rotationDegrees, flip, red, green, blue, source,
                moving, exemplar, label);
    }

    public Markup.BoundingBox toProtocolBuffer() {
        Markup.BoundingBox.Builder builder = Markup.BoundingBox.newBuilder()
                .setX(x)
                .setY(y)
                .setWidth(width)
                .setHeight(height)
                .setRotationDegrees(rotationDegrees)
                .setFlip(flip)
                .setRed(red)
                .setBlue(blue)
                .setGreen(green)
                .setSource(Markup.BoundingBoxSource.valueOf(source.toString().toUpperCase()))
                .setMoving(moving)
                .setExemplar(exemplar);
        label.ifPresent(builder::setLabel);
        return builder.build();
    }
}
