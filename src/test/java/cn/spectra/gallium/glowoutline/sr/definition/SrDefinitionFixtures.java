package cn.spectra.gallium.glowoutline.sr.definition;

final class SrDefinitionFixtures {
    private SrDefinitionFixtures() {}

    static final String LEGACY = """
            {
              "sr": {
                "enabled": true,
                "worlds": {
                  "*": {
                    "enabled": true,
                    "upscale_config": {
                      "before_upscale_shader_name": "composite2",
                      "sr_internal_texture_format": "r11g11b10f",
                      "input_textures": {
                        "color": {"enabled": true, "src": "colortex0", "region": [0, 0, -1, -1]},
                        "depth": {"enabled": true, "src": "depthtex", "region": [0, 0, -1, -1]}
                      },
                      "output_textures": {
                        "upscaled_color": {"enabled": true, "target": ["colortex0"], "region": [0, 0, -2, -2]}
                      }
                    }
                  }
                }
              },
              "sr_jitter": {
                "enabled": true,
                "worlds": {"*": {"enabled": true}}
              }
            }
            """;

    static String versioned(int version) {
        return """
                {
                  "schema_version": %d,
                  "profiles": {
                    "*": {
                      "enabled": true,
                      "upscale": {
                        "enabled": true,
                        "trigger": {"type": "after", "pass": "composite2"},
                        "internal_format": "rgba16f",
                        "inputs": {
                          "color": {"enabled": true, "src": "colortex0", "region": [0, 0, -1, -1]},
                          "depth": {"enabled": true, "src": "depthtex", "region": [4, 2, 1280, 720]}
                        },
                        "outputs": {
                          "upscaled_color": {"enabled": true, "target": ["colortex0", "colortex5"], "region": [0, 0, -2, -2]}
                        }
                      },
                      "jitter": {
                        "enabled": true,
                        "source": "shaderpack",
                        "source_config": {
                          "jitter_offset": {"source": "uniform", "type": "vector2f", "value": "taaJitter"},
                          "jitter_sequence_length": {"source": "const", "type": "int", "value": 8}
                        }
                      }
                    },
                    "-1": {
                      "enabled": false,
                      "upscale": {"enabled": false},
                      "jitter": {"enabled": false, "source": "mod"}
                    }
                  }
                }
                """.formatted(version);
    }

    static final String MACRO_WRAPPED = """
            #if SR_ENABLE
            {
              "schema_version": 3,
              "profiles": {}
            }
            #else
            {
              "schema_version": 3,
              "profiles": {}
            }
            #endif
            """;
}
