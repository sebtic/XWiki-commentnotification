package org.projectsforge.xwiki.commentnotification;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.mail.Message.RecipientType;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.mail.MailListener;
import org.xwiki.mail.MailSender;
import org.xwiki.mail.MailSenderConfiguration;
import org.xwiki.mail.XWikiAuthenticator;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.model.reference.RegexEntityReference;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.internal.event.XObjectAddedEvent;
import com.xpn.xwiki.internal.event.XObjectEvent;
import com.xpn.xwiki.internal.event.XObjectUpdatedEvent;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.NumberProperty;

@Component
@Named("CommentNotificationEventListener")
@Singleton
public class CommentNotificationEventListener implements EventListener {
  private static final EntityReference COMMENT_CLASS_REFERENCE = new EntityReference("XWikiComments",
      EntityType.DOCUMENT, new EntityReference("XWiki", EntityType.SPACE));

  private static final EntityReference USER_CLASS_REFERENCE = new EntityReference("XWikiUsers", EntityType.DOCUMENT,
      new EntityReference("XWiki", EntityType.SPACE));

  /**
   * The reference to match class XWiki.Comment on whatever wiki.
   */
  private static final RegexEntityReference COMMENTCLASS_REFERENCE = new RegexEntityReference(
      Pattern.compile(".*:XWiki.XWikiComments\\[\\d*\\]"), EntityType.OBJECT);

  @Inject
  private Logger logger;

  @Inject
  private MailSenderConfiguration configuration;

  @Inject
  private MailSender mailSender;

  @Inject
  @Named("database")
  private MailListener databaseMailListener;

  @Override
  public List<Event> getEvents() {
    return Arrays.<Event> asList(new XObjectAddedEvent(COMMENTCLASS_REFERENCE),
        new XObjectUpdatedEvent(COMMENTCLASS_REFERENCE));
  }

  @Override
  public String getName() {
    return "CommentNotificationEventListener";
  }

  @Override
  public void onEvent(Event event, Object source, Object data) {
    XWikiDocument document = (XWikiDocument) source;
    XWikiContext context = (XWikiContext) data;

    logger.debug("CommentEventListener::onEvent");

    try {
      XObjectEvent objectEvent = (XObjectEvent) event;

      BaseObject commentObject = document.getXObject((ObjectReference) objectEvent.getReference());

      // there is no comment !!!
      if (commentObject == null) {
        return;
      }

      String lastAuthorEmail = context.getWiki().getDocument(document.getAuthorReference(), context)
          .getXObject(USER_CLASS_REFERENCE).getStringValue("email");

      logger.debug("lastAuthorEmail is {}", lastAuthorEmail);

      // there is no destination email => stop here
      if (StringUtils.isBlank(lastAuthorEmail)) {
        return;
      }

      // Get comment
      String comment = commentObject.getStringValue("comment");

      // Step 1: Create a JavaMail Session
      // ... with authentication:
      Session session = Session.getInstance(configuration.getAllProperties(), new XWikiAuthenticator(configuration));

      // Step 2: Create the Message to send
      MimeMessage message = new MimeMessage(session);
      if (event instanceof XObjectAddedEvent) {
        message.setSubject("[XWiki] Comment added to " + document.toString());
      } else {
        message.setSubject("[XWiki] Comment updated on " + document.toString());
      }
      message.addRecipient(RecipientType.TO, new InternetAddress(lastAuthorEmail));

      // if it is a reply to a previous comment, mail the previous comment
      // author
      NumberProperty replyTo = (NumberProperty) commentObject.get("replyto");
      if (StringUtils.isNotBlank(replyTo.toText())) {
        BaseObject previousCommentObject = document.getXObject(COMMENT_CLASS_REFERENCE, (Integer) replyTo.getValue());
        String replytoAuthor = previousCommentObject.getStringValue("author");
        String replytoAuthorEmail = context.getWiki().getDocument(replytoAuthor, context)
            .getXObject(USER_CLASS_REFERENCE).getStringValue("email");
        if (StringUtils.isNotBlank(replytoAuthorEmail)) {
          message.addRecipient(RecipientType.TO, new InternetAddress(replytoAuthorEmail));
        }
      }

      // Step 3: Add the Message Body
      Multipart multipart = new MimeMultipart("mixed");
      MimeBodyPart bodyPart = new MimeBodyPart();
      bodyPart.setContent(comment, "text/plain");
      multipart.addBodyPart(bodyPart);
      message.setContent(multipart);

      // Step 4 : Send the mail asynchronously with a Database Mail Listener
      mailSender.sendAsynchronously(Arrays.asList(message), session, databaseMailListener);
      logger.debug("Message sent");
    } catch (Exception e) {
      this.logger.error("Failure in comment listener", e);
    }

  }
}
