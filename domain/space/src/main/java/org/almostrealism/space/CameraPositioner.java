/*
 * Copyright 2016 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.almostrealism.space;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.BoundingSolid;
import org.almostrealism.projection.PinholeCamera;

/**
 * Auto positions a camera for viewing a scene.
 *
 * @author Dan Chivers
 */
public class CameraPositioner {
    /** The pinhole camera to be repositioned. */
    private final PinholeCamera camera;

    /** The scene whose bounding solid is used to determine the camera position. */
    private final Scene scene;

    /** The axis direction along which the camera is oriented. */
    private final Vector cameraDirection;

    /** The computed world-space location to place the camera. */
    private Vector location;

    /** The viewing direction from the camera towards the scene centre. */
    private Vector direction;

    /**
     * Constructs a new default {@link CameraPositioner}.
     * The {@link PinholeCamera} will be directed along the -Z axis.
     *
     * @param camera The {@link PinholeCamera} to position.
     * @param scene The {@link Scene} to view.
     */
    public CameraPositioner(PinholeCamera camera, Scene scene) {
        this(camera, scene, new Vector(0, 0, -1));
    }

    /**
     * Constructs a new {@link CameraPositioner}.
     * The {@link PinholeCamera} will be directed along the given cameraDirection {@link Vector}.
     *
     * @param camera The {@link PinholeCamera} to position.
     * @param scene The {@link Scene} to view.
     * @param cameraDirection The {@link Vector} along which to point the {@link PinholeCamera}.
     */
    public CameraPositioner(PinholeCamera camera, Scene scene, Vector cameraDirection) {
        this.camera = camera;
        this.scene = scene;
        this.cameraDirection = cameraDirection;
        calculatePosition();
    }

    /**
     * Computes the camera location and viewing direction based on the scene bounding solid
     * and the configured camera direction axis.
     */
    private void calculatePosition() {
        // Scene bounding sphere.
        BoundingSolid sceneBounds = scene.calculateBoundingSolid();
        Vector sceneMidpoint = (Vector) sceneBounds.center;
        double sceneBoundingRadius = Math.sqrt(Math.pow(sceneBounds.dx/2, 2) + Math.pow(sceneBounds.dy/2, 2) + Math.pow(sceneBounds.dz/2, 2));

        // FOVs.
        double[] fovs = camera.getFOV();
        double horizontalFov = fovs[0];
        double verticalFov = fovs[1];

        // Camera distance.
        double distance = sceneBoundingRadius / Math.sin(Math.min(horizontalFov, verticalFov));

        // Move the camera.
        Vector moveCamera = cameraDirection.divide(cameraDirection.length()).multiply(-1);
        Vector cameraPos = moveCamera.multiply(distance);

        // Camera Position.
        location = sceneMidpoint.add(cameraPos);

        // Viewing direction.
        direction = sceneMidpoint.subtract(location);
    }

    /**
     * Returns the computed world-space camera location.
     *
     * @return the camera position
     */
    public Vector getLocation() {
        return location;
    }

    /**
     * Returns the computed viewing direction from the camera towards the scene centre.
     *
     * @return the viewing direction vector
     */
    public Vector getViewingDirection() {
        return direction;
    }
}