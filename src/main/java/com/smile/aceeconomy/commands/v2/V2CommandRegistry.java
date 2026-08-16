package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.command.CommandSpec;

import java.util.List;
import java.util.Objects;

/** Builds and registers the complete v2 command presentation surface. */
public final class V2CommandRegistry {

    private final List<CommandSpec> specs;

    private V2CommandRegistry(CommandServices services) {
        this.specs = List.of(
                MoneyCommandSpec.create(services),
                PayCommandSpec.create(services),
                WithdrawCommandSpec.create(services),
                BaltopCommandSpec.create(services),
                BankCommandSpec.create(services),
                AceEcoCommandSpec.create(services));
    }

    public static V2CommandRegistry create(CommandServices services) {
        return new V2CommandRegistry(Objects.requireNonNull(services, "services"));
    }

    public List<CommandSpec> specs() {
        return specs;
    }

    public void register(CommandRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        specs.forEach(registry::register);
    }
}
