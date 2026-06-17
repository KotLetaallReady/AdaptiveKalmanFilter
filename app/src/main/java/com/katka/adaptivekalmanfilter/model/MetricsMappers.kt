package com.katka.adaptivekalmanfilter.model

import com.katka.model.AccuracyMetrics

/** Format engine [AccuracyMetrics] for display; empty/NaN → the "—" placeholder. */
fun AccuracyMetrics.toMetricsUiModel(): MetricsUiModel =
    if (sampleCount < 1 || rmse.isNaN()) {
        MetricsUiModel.EMPTY
    } else {
        MetricsUiModel(
            rmse = "%.2f м".format(rmse),
            mae = "%.2f м".format(mae),
            maxError = "%.2f м".format(maxError),
            stability = "%.3f м".format(stability),
            lag = "%.1f шаг".format(lag),
            sampleCount = sampleCount
        )
    }
