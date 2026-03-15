import sys

path = 'app/src/main/java/de/witt3d_gis/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

start_indices = [i for i, line in enumerate(lines) if "private fun refreshFeatureLayer(belowLayerId: String? = null) {" in line]

if len(start_indices) > 1:
    second_start = start_indices[1]
    lines = lines[:second_start] + [lines[-1]]

clean_func = """    private fun refreshFeatureLayer(belowLayerId: String? = null) {
        if (!::map.isInitialized) return
        val style = map.style ?: return

        var targetBelowId = belowLayerId
        if (targetBelowId == null) {
            for (layer in style.layers) {
                if (layer.id.contains("label", ignoreCase = true) || layer.id.contains("symbol", ignoreCase = true)) {
                    targetBelowId = layer.id
                    break
                }
            }
        }

        val manualConfig = wmsLayers.find { it.id == "internal_manual" }
        if (manualConfig != null && !manualConfig.enabled) {
            style.removeLayer("import-fill-layer")
            style.removeLayer("import-line-layer")
            style.removeLayer("import-circle-layer")
            style.removeLayer("import-label-layer")
            return
        }

        try {
            val collection = FeatureCollection.fromFeatures(ArrayList(currentFeatures))
            val source = style.getSource("import-source") as? GeoJsonSource

            if (source != null) {
                source.setGeoJson(collection)
            } else {
                val options = org.maplibre.android.style.sources.GeoJsonOptions()
                    .withBuffer(512)
                    .withTolerance(0f)
                    .withMaxZoom(28)
                style.addSource(GeoJsonSource("import-source", collection, options))
            }

            if (style.getLayer("import-fill-layer") == null) {
                val layer = FillLayer("import-fill-layer", "import-source").apply {
                    setProperties(
                        PropertyFactory.fillColor(Color.argb(50, 255, 0, 0)),
                        PropertyFactory.fillOutlineColor(Color.RED),
                        PropertyFactory.fillAntialias(true)
                    )
                    minZoom = 0f
                    maxZoom = 45f
                }
                if (targetBelowId != null) style.addLayerBelow(layer, targetBelowId) else style.addLayer(layer)
            }
            if (style.getLayer("import-line-layer") == null) {
                val layer = LineLayer("import-line-layer", "import-source").apply {
                    setProperties(
                        PropertyFactory.lineColor(Color.RED),
                        PropertyFactory.lineWidth(2f),
                        PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                    )
                    minZoom = 0f
                    maxZoom = 45f
                }
                if (targetBelowId != null) style.addLayerBelow(layer, targetBelowId) else style.addLayer(layer)
            }
            if (style.getLayer("import-circle-layer") == null) {
                val layer = CircleLayer("import-circle-layer", "import-source").apply {
                    setProperties(
                        PropertyFactory.circleRadius(6f),
                        PropertyFactory.circleColor(Color.RED),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(Color.WHITE)
                    )
                    minZoom = 0f
                    maxZoom = 45f
                }
                if (targetBelowId != null) style.addLayerBelow(layer, targetBelowId) else style.addLayer(layer)
            }
            if (style.getLayer("import-label-layer") == null) {
                val layer = SymbolLayer("import-label-layer", "import-source").apply {
                    setProperties(
                        PropertyFactory.textField(
                            org.maplibre.android.style.expressions.Expression.format(
                                org.maplibre.android.style.expressions.Expression.formatEntry(
                                    org.maplibre.android.style.expressions.Expression.coalesce(org.maplibre.android.style.expressions.Expression.get("name"), org.maplibre.android.style.expressions.Expression.literal(""))
                                ),
                                org.maplibre.android.style.expressions.Expression.formatEntry(
                                    org.maplibre.android.style.expressions.Expression.switchCase(
                                        org.maplibre.android.style.expressions.Expression.has("notes"),
                                        org.maplibre.android.style.expressions.Expression.concat(org.maplibre.android.style.expressions.Expression.literal("\\n"), org.maplibre.android.style.expressions.Expression.get("notes")),
                                        org.maplibre.android.style.expressions.Expression.literal("")
                                    )
                                )
                            )
                        ),
                        PropertyFactory.textSize(14f),
                        PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
                        PropertyFactory.textColor(Color.BLACK),
                        PropertyFactory.textHaloColor(Color.WHITE),
                        PropertyFactory.textHaloWidth(2.0f),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true)
                    )
                    minZoom = 0f
                    maxZoom = 45f
                }
                if (targetBelowId != null) style.addLayerBelow(layer, targetBelowId) else style.addLayer(layer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in refreshFeatureLayer: ${e.message}")
        }
    }
"""

first_start = start_indices[0]
first_end = first_start + 1
while first_end < len(lines) and "    private fun" not in lines[first_end] and lines[first_end] != "}\\n":
    first_end += 1

lines[first_start:first_end] = [clean_func + "\\n"]

with open(path, 'w') as f:
    f.writelines(lines)
