package com.example.myapplication;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that brings up a real Filament surface. CI excludes these with
 * {@code notAnnotation}: its emulator runs {@code -gpu swiftshader_indirect}, where a single
 * Filament surface took the device offline in one run out of three and failed every other test in
 * the run with it (see {@code AndroidBoard3DUiTest.dialogRendersAboveSurfaceView}). They run
 * locally and on hardware-GPU devices.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Filament3D {}
