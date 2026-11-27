package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class DelimitedMatchSubjectTest {
    private static void execute(String program, String expected) throws Exception {
        OwnershipChecker.check(TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).option("engine.WarnInterpreterOnly", "false")
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "delimited-subject.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals(expected, output.toString(StandardCharsets.UTF_8));
    }

    @Test void scalarAndOperatorBoundaries() throws Exception {
        execute("""
                pub routine main(): void {
                    const int v = 7;
                    stdio.stdout.write(match |v| over on 7 -> v * 6, on _ -> 0 end);
                    stdio.stdout.write(":");
                    stdio.stdout.write(match |(2 | 4)| over on 6 -> 42, on _ -> 0 end);
                    stdio.stdout.write(":");
                    stdio.stdout.write(match |true || false| over on true -> 42, on false -> 0 end);
                    return;
                }
                """, "42:42:42");
    }

    @Test void explicitProductPreservesOrderAndShape() throws Exception {
        execute("""
                fnc left(): int { stdio.stdout.write("L"); return 10; }
                fnc right(): int { stdio.stdout.write("R"); return 32; }
                pub routine main(): void {
                    stdio.stdout.write(match |left(), right()| over
                        on (10, 32) -> 42, on _ -> 0 end);
                    stdio.stdout.write(":");
                    stdio.stdout.write(match |(10, 20), 12| over
                        on ((10, 20), 12) -> 42, on _ -> 0 end);
                    return;
                }
                """, "LR42:42");
    }

    @Test void scalarSwitchKeepsCaseAndNonlocalReturn() throws Exception {
        execute("""
                fnc choose(int v): int {
                    switch |v| over
                        case 7 -> { return 42; }
                        default -> { return 0; }
                    end;
                    return 99;
                }
                pub routine main(): void {
                    stdio.stdout.write(choose(7));
                    return;
                }
                """, "42");
    }

    @Test void rejectsMalformedHeadersWithoutChangingOrdinaryPipes() {
        for (String expression : new String[] {
            "match | | over on _ -> 42 end",
            "match |2 | 4| over on _ -> 42 end",
            "match |2,| over on _ -> 42 end",
            "match |2 over on _ -> 42 end",
            "7 |> match |x| over on _ -> x end"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc main(): int { return " + expression + "; }"), expression);
        }
        assertDoesNotThrow(() -> Parser.parse("pub fnc main(): int { return (2 | 4); }"));
    }

    @Test void unresolvedExplicitSubjectIsNotAnImplicitBinder() {
        assertThrows(RuntimeException.class, () -> TypeChecker.check(Parser.parse(
                "pub fnc main(): int { return match |missing_subject| over on _ -> 42 end; }")));
    }
}
