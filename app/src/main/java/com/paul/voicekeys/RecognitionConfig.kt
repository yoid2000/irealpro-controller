package com.paul.voicekeys

import com.k2fsa.sherpa.onnx.*
import java.util.Locale

object RecognitionConfig {
    const val BIAS_SCORE = 0.5f
    fun hotwords(mappings: List<CommandMapping>): String = mappings.joinToString("/") {
        // This model's English tokens are uppercase. Lowercase silently fails to
        // bias their acoustic paths even though it is valid input to SentencePiece.
        validateWord(it.word).uppercase(Locale.ROOT)
    }

    fun create(biased: Boolean) = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(encoder = "kws/encoder.onnx",
                decoder = "kws/decoder.onnx", joiner = "kws/joiner.onnx"),
            tokens = "kws/tokens.txt", numThreads = 2, provider = "cpu", modelType = "zipformer2",
            modelingUnit = if (biased) "bpe" else "",
            bpeVocab = if (biased) "kws/bpe.vocab" else "",
        ),
        decodingMethod = if (biased) "modified_beam_search" else "greedy_search",
        maxActivePaths = 4, hotwordsScore = BIAS_SCORE, enableEndpoint = true,
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0f),
            // Endpoint reset is separate from immediate word emission. Leave
            // room for delayed decoder output instead of truncating a segment.
            rule2 = EndpointRule(true, 1.6f, 0f),
            rule3 = EndpointRule(false, 0f, 3600f),
        ),
    )
}
