package com.streameast.segmenter.model;

import lombok.Data;

@Data
public class Watermark {
    private String text;
    private String imagePath;
    private int x = 10; // Default position
    private int y = 10;
    private int size = 24; // Default font size
    private String color = "white"; // Default color
    private float opacity = 0.8f; // Default opacity
}
