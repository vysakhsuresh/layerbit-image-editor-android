# Recipes and presets are kept as JSON keyed by the engine's own field and enum names, written
# and read by hand in engine/RecipeJson.kt - never by reflection - so nothing here needs keeping
# by name. Enum valueOf is used on MarkKind, Look and OutputFormat; R8 keeps those on its own
# because the calls are visible.
#
# No ML, no PDF library, no reflection-based serialiser, no native code. Kept empty on purpose:
# a rule added here is a dependency that reflects, and that is a review, not a tidy-up.
