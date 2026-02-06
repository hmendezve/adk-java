package com.google.adk.a2a.converters;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.agents.InvocationContext;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converter for ADK Events to A2A Messages.
 *
 * <p>**EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do not
 * use in production code.
 */
public final class EventConverter {
  private static final Logger logger = LoggerFactory.getLogger(EventConverter.class);

  private EventConverter() {}

  public static Optional<Message> convertEventsToA2AMessage(InvocationContext context) {
    if (context.session().events().isEmpty()) {
      logger.warn("No events in session, cannot convert to A2A message.");
      return Optional.empty();
    }

    List<Part<?>> parts = new ArrayList<>();

    context.session().events().forEach(event -> parts.addAll(contentToParts(event.content())));
    parts.addAll(contentToParts(context.userContent()));

    if (parts.isEmpty()) {
      logger.warn("No suitable content found to build A2A request message.");
      return Optional.empty();
    }

    return Optional.of(
        new Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .parts(parts)
            .role(Message.Role.USER)
            .build());
  }

  public static ImmutableList<Part<?>> contentToParts(Optional<Content> content) {
    if (content.isPresent() && content.get().parts().isPresent()) {
      return content.get().parts().get().stream()
          .map(PartConverter::fromGenaiPart)
          .collect(toImmutableList());
    }
    return ImmutableList.of();
  }
}
