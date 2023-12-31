package com.riscure.dobby.clang

import arrow.core.*
import com.riscure.dobby.shell.Val
import org.apache.commons.lang3.SystemUtils
import com.riscure.dobby.shell.Arg as ShellArg

/**
 * The type of compile-command option lists.
 */
typealias Options = List<Arg>

/**
 * The semantic model of Clang compilation commands
 */
data class Command(val optArgs: Options, val positionalArgs: List<String>) {
    /**
     * Return a command without [opt] or any of its aliases.
     */
    fun filter(spec: Spec, opts: Collection<OptionSpec>): Command {
        return this.copy(
            optArgs = optArgs.filter { (argSpec, _) ->
                !opts.any { blacklisted -> spec.equal(argSpec, blacklisted) }
            }
        )
    }

    /**
     * Check if this command contains [opt] or any of its aliases.
     */
    fun contains(spec: Spec, opts: Set<OptionSpec>): Boolean {
        return optArgs.any { (argSpec, _) -> opts.any { listed -> spec.equal(listed, argSpec)} }
    }

    /**
     * Returns this command without [opt] or any of its aliases.
     */
    fun filter(spec: Spec, opt: OptionSpec): Command = filter(spec, setOf(opt))

    /**
     * Returns true iff this command contains [opt] or any of its aliases.
     */
    fun contains(spec: Spec, opt: OptionSpec): Boolean = contains(spec, setOf(opt))

    /**
     * Removes any alias of [arg.opt] and then adds [arg].
     */
    fun replace(spec: Spec, arg: Arg): Command = filter(spec, arg.opt) + arg

    /**
     * Removes any alias of [arg.opt] and then adds [arg].
     */
    fun replace(spec: Spec, args: List<Arg>): Command = filter(spec, args.map { it.opt }) + args

    /**
     * Adds [arg] to the (rear of) the options of this command
     */
    operator fun plus(arg: Arg) = this.copy(optArgs = optArgs + arg)

    /**
     * Adds [arg] to the (rear of) the options of this command
     */
    operator fun plus(args: List<Arg>) = this.copy(optArgs = optArgs + args)

    /**
     * Adds [positional] to the rear of the positional arguments of this command.
     */
    operator fun plus(positional: String) = this.copy(positionalArgs = positionalArgs + positional)

    /**
     * Adds [include] by setting multiple -isystem/-iquote flags.
     */
    fun includes(spec: Spec, include: IncludePath) = this.copy(
        optArgs = optArgs
                + include.isystem.map { Arg(spec["isystem"], it.toString()) }
                + include.iquote .map { Arg(spec["iquote"], it.toString()) }
    )

    /**
     * Produces the arguments 'raw', without any escaping of the arguments.
     * This is suitable for passing the arguments directly to Java's ProcessBuilder, for example.
     */
    fun toArguments(): List<String> =
        optArgs
            .flatMap { it.toArguments() }
            .plus(positionalArgs)

    /**
     * Produces the arguments for passing it to Runtime.exec(String[]) on the host platform.
     * Note that this is not the same as shell escaping for the host platform (!).
     *
     * The output of this function is really only suitable for exec'ing it now (including
     * use in [ProcessBuilder]. It should never end up in a file or even in a model,
     * because there is nothing portable about the returned value.
     */
    fun toExecArguments(): List<String> = when {
        SystemUtils.IS_OS_WINDOWS -> toWinExecArguments()
        else                      -> toArguments()
    }

    /**
     * Produces the arguments for passing it to Runtime.exec(String[]) on windows.
     *
     * The output of this function is really only suitable for exec'ing it now.
     * It should never end up in a file or even in a model, because there is nothing portable about the returned value.
     */
    fun toWinExecArguments(): List<String> =
        toArguments()
            // TODO, this cannot possibly be right/complete
            .map { it.replace("\"", "\"\"\"") }

    /**
     * This escapes the arguments for a POSIX shell.
     */
    fun toPOSIXArguments(): List<String> =
        optArgs
            .flatMap { it.shellify() }
            .plus(positionalArgs.map { ShellArg.quote(it) })
            .map { it.toString() }

    companion object : arrow.typeclasses.Monoid<Command> {
        @JvmStatic
        override fun empty(): Command = Command(listOf(), listOf())
        override fun Command.combine(b: Command): Command =
            Command(optArgs.plus(b.optArgs), positionalArgs.plus(b.positionalArgs))

        @JvmStatic
        fun reads(arguments: List<String>) = ClangParser.parseArguments(arguments)
    }
}

/**
 * Clang compilation optional arguments, with semantic info from the spec attached.
 * The number of values is determined by the [opt] spec.
 */
data class Arg(val opt: OptionSpec, val values: List<String>) {
    constructor(opt: OptionSpec, value: String):  this(opt, listOf(value))
    constructor(opt: OptionSpec):  this(opt, listOf())

    /**
     * Join arguments according to the option specification for passing via e.g. ProcessBuilder
     */
    fun toArguments(): List<String> = opt.appearance()
        .let { op ->
            when (opt.type) {
                OptionType.Joined            -> listOf(op + values.firstOrNone().getOrElse { "" }) // single argument
                OptionType.CommaJoined       -> {
                    val values = values.joinToString(separator = ",") { it }
                    listOf(op + values)
                }
                OptionType.JoinedAndSeparate -> listOf(op + values[0]) + values.drop(1)
                OptionType.JoinedOrSeparate  -> listOf(op) + values
                OptionType.Separate          -> listOf(op) + values
                OptionType.Toggle            -> listOf(op)
                is OptionType.MultiArg       -> listOf(op) + values
            }
        }

    /**
     * Join arguments according to the option specification for passing via the shell.
     */
    fun shellify(): List<ShellArg> = ShellArg(Val.raw(opt.appearance()))
        .let { op ->
            when (opt.type) {
                OptionType.Joined            -> listOf(op + ShellArg.quote(values)) // single argument
                OptionType.CommaJoined       -> {
                    // TODO fix escaping here
                    val values = Val.raw(values.joinToString(separator = ",") { it })
                    listOf(op) + ShellArg(listOf(values))
                }
                OptionType.JoinedAndSeparate -> listOf(op + ShellArg.quote(values[0])) + ShellArg.quote(values.drop(1))
                OptionType.JoinedOrSeparate  -> listOf(op) + values.map { ShellArg.quote(it) }
                OptionType.Separate          -> listOf(op) + values.map { ShellArg.quote(it) }
                OptionType.Toggle            -> listOf(op)
                is OptionType.MultiArg       -> listOf(op) + values.map { ShellArg.quote(it) }
            }
        }

    /**
     * Join arguments according to the option specification to an unescaped string option.
     */
    override fun toString(): String = opt.appearance()
        .let { op ->
            when (opt.type) {
                OptionType.Joined            -> op + values.joinToString("")
                OptionType.CommaJoined       -> op + values.joinToString(",")
                OptionType.JoinedAndSeparate -> op + values[0] + " " + values.joinToString(" ")
                OptionType.JoinedOrSeparate  -> op + values.joinToString(" ")
                OptionType.Separate          -> op + " " + values.joinToString(" ")
                OptionType.Toggle            -> op
                is OptionType.MultiArg       -> op + values.joinToString(" ")
            }
        }

    companion object {
        // TODO these should not be here, because they hardcode the specification.
        // This class should not be biased towards the clang 11 spec.

        /**
         * Read a single option argument with a possible [tail] of separated values.
         */
        @Deprecated("use ClangParser.parseOption instead", ReplaceWith("ClangParser.parseOption(head, *tail)"))
        @JvmStatic fun reads(head: String, vararg tail: String) = ClangParser.parseOption(head, *tail)

        @Deprecated("use ClangParser.parseOptions instead", ReplaceWith("ClangParser.parseOptions(args.flatten())"))
        @JvmStatic fun readMany(args: List<List<String>>): Either<String, List<Arg>> = args
            .flatMap { parts ->
                if (parts.isEmpty()) {
                    listOf()
                } else {
                    listOf(ClangParser.parseOption(parts[0], *parts.drop(1).toTypedArray()))
                }
            }
            .sequence()
    }
}
