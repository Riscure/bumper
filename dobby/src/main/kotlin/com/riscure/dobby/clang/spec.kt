package com.riscure.dobby.clang

import arrow.core.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.net.URL

sealed class OptionType {
    /* a flag, like -v */
    object Toggle : OptionType()
    /* an option whose argument is attached to it, like -oafile.o */
    object Joined : OptionType()
    /* an option whose arguments are attached to it, but comma separated, like archs=x,y,z */
    object CommaJoined : OptionType()
    /* an option whose argument appears after a space */
    object Separate : OptionType()
    /* -o file.o or -ofile.o  both permitted */
    object JoinedOrSeparate : OptionType()
    /* -opt<arg1> <arg2> */
    object JoinedAndSeparate : OptionType()
    /* -opt <arg1> <arg2> .. <argn> */
    data class MultiArg(val num: Int): OptionType()
}

/**
 * A specification of a compiler option.
 */
data class OptionSpec(
    /* Unique name that the clang spec uses to reference this option */
    val key       : String,
    /* Human-readable name */
    val name      : String,
    /* a list of valid prefixes, e.g. [ "-", "--", "/" ] */
    val prefixes  : Set<String>,
    /* the type specifies how it is parsable */
    val type      : OptionType = OptionType.Toggle,
    /**
     *  Which option it aliases according to the spec, if any.
     *  This is not always complete. From this specified set of aliases
     *  we build the full equivalence class in the spec.
     **/
    val aliasFor  : Option<Alias> = None,
) {
    fun allAppearances(): Set<String> =
        // Empty list of prefixes results in 0 appearances
        // This should be OK, because only KIND_INPUT and KIND_UNKNOWN options have no prefixes.
        prefixes
            .map { prefix -> prefix.plus(name) }
            .toSet()
}

// FIXME we should distinguish alias for key without any values,
// from an alias regardless of the value.
data class Alias(val forKey: String, val withValue: List<String>)

/**
 * Captures the cli specification; i.e., all available options, with their behavior.
 */
data class Spec(val optionsByKey: Map<String, OptionSpec>) {

    fun get(key: String) = optionsByKey[key].toOption()

//    val byName: Map<String, Set<OptionSpec>> by lazy {
//        val m = mutableMapOf<String, MutableSet<OptionSpec>>()
//
//        this.optionsByKey.forEach { (k, v) ->
//            val matches = m.getOrDefault(v.name, mutableSetOf<OptionSpec>())
//            matches.add(v)
//        }
//
//        m
//    }

    fun options() = optionsByKey.values

    /**
     * Map of aliases for every option by their key.
     * An alias may only be an alias in combination with a specific argument.
     * For example, the map should contain an entry: mcpu_EQ to { .., "mv5"},
     * If we look at byName["mv5"], we find that this option is equivalent
     * to "mcpu=hexagonv5", encoded by the fact that aliasFor = Some(Alias("mcpu_EQ", listOf("hexagonv5"))).
     *
     * FIXME: this is not computing the transitive alias closure...
     */
    val aliasing: Map<String, Set<OptionSpec>> by lazy {
        val ali:Map<String,MutableSet<OptionSpec>> = optionsByKey.mapValues { mutableSetOf() }

        // iterate over all options,
        // add a reverse alias mapping to all declared aliases
        optionsByKey.forEach { (_, opt) -> // opt =~ mv5
            opt.aliasFor.tap { alias ->    // alias =~ Alias(mcpu_EQ, listOf("hexagonv5"))
                ali[opt.key]          // we add
                    .toOption()
                    .tap { it.add(opt) }
            }
        }

        ali
    }

    companion object {
        private val specURL: URL = Spec::class
            .java.getResource("/clang.options.json")
            .let {
                if (it == null) {
                    throw RuntimeException("Missing resource 'clang.options.json'")
                }
                it
            }

        @OptIn(ExperimentalSerializationApi::class)
        private fun specJSON(): JsonElement = Json.decodeFromStream(specURL.openStream())

        /* A parsed Clang 11 spec */
        val clang11: Spec by lazy { SpecReader.readSpec(specJSON()) }
    }
}


/**
 * Return the set containing all given options plus their aliases.
 */
context(Spec)
fun Set<OptionSpec>.aliasClosure(): Set<OptionSpec> =
    this.flatMap {  option -> aliasing.getOrDefault(option.key, setOf()) }
        .plus(this)
        .toSet()

/**
 * A not so safe conversion from the JSON option spec for Clang,
 * to a Spec instance.
 *
 * We only use it to read a prepackaged json file, so if it works on that one,
 * it is good enough.
 */
private object SpecReader {

    fun readSpec(json: JsonElement): Spec {
        val jobj = json.jsonObject

        // some meta-keys are prefixed with an exclamation mark
        // the rest are option defs
        val opts = jobj
            .filterKeys { !it.startsWith("!") }
            .filterValues {
                try {
                    // we select defs by the superclass "Option"
                    it.jsonObject["!superclasses"]!!.jsonArray
                        .map { it.jsonPrimitive.content }
                        .contains("Option")
                } catch (e:Exception) { false }
            }
            .flatMap { (key, value) ->
                readsClangOpt(key, value)
                    .map { spec: OptionSpec -> mapOf(key to spec) }
                    .getOrElse { mapOf() }
            }

        return Spec(opts)
    }

    private fun readClangKind(json: JsonElement): Option<OptionType> {
        val err = "Could not decode json for clang kind %s"
        return try {
            val kindEl = json.jsonObject["Kind"]!!.jsonObject["def"]!!
            when (kindEl.jsonPrimitive.content) {
                "KIND_COMMAJOINED" -> OptionType.CommaJoined.some()
                "KIND_JOINED_AND_SEPARATE" -> OptionType.JoinedAndSeparate.some()
                "KIND_JOINED" -> OptionType.Joined.some()
                "KIND_SEPARATE" -> OptionType.Separate.some()
                "KIND_FLAG" -> OptionType.Toggle.some()
                "KIND_JOINED_OR_SEPARATE" -> OptionType.JoinedOrSeparate.some()
                "KIND_MULTIARG" -> {
                    val num = json.jsonObject["NumArgs"]!!.jsonPrimitive.content
                    OptionType.MultiArg(num.toInt(10)).some()
                }
                else -> None
            }
        }
        catch (e: Throwable) { throw RuntimeException(err.format(json)) }
    }

    /**
     *  Read an option descriptions from the json spec.
     * @throws RuntimeException when the json is unexpected
     */
    fun readsClangOpt(key: String, json: JsonElement): Option<OptionSpec> =
        try {
            val jobj = json.jsonObject
            readClangKind(jobj)
                .map { kind ->
                    OptionSpec(
                        key,
                        jobj["Name"]!!.jsonPrimitive.content,
                        jobj["Prefixes"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                        kind,
                        readsAliasSpec(jobj)
                    )
                }
        } catch (e : Throwable) {
            throw RuntimeException("Failed to parse json as clang option spec.", e)
        }

    private fun readsAliasSpec(json: JsonObject): Option<Alias> =
        json["Alias"]
            .toOption()
            .filter { it !is JsonNull }
            .flatMap { it.jsonObject["def"].toOption().map { d -> d.jsonPrimitive.content }}
            .map { aliasKey ->
                Alias(aliasKey,
                    json["AliasArgs"]!!
                        .jsonArray
                        .map { el -> el.jsonPrimitive.content }
                )
            }
}