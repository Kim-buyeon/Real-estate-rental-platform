package com.duri.rentalplatform.domain.property.vo;

/** 위경도 사각 범위. 경계를 포함한다(SQL BETWEEN). */
public record BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {
}
