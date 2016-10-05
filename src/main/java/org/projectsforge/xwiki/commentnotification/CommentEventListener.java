package org.projectsforge.xwiki.commentnotification;

import java.util.Arrays;
import java.util.List;

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
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.internal.event.CommentAddedEvent;
import com.xpn.xwiki.internal.event.CommentUpdatedEvent;
import com.xpn.xwiki.objects.BaseObject;

@Component
@Named("CommentEventListener")
@Singleton
public class CommentEventListener implements EventListener {
  private static final EntityReference COMMENT_CLASS_REFERENCE = new EntityReference("XWikiComments",
      EntityType.DOCUMENT, new EntityReference("XWiki", EntityType.SPACE));

  private static final EntityReference USER_CLASS_REFERENCE = new EntityReference("XWikiUsers", EntityType.DOCUMENT,
      new EntityReference("XWiki", EntityType.SPACE));

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
    return Arrays.<Event> asList(new CommentAddedEvent(), new CommentUpdatedEvent());
  }

  @Override
  public String getName() {
    return "CommentEventListener";
  }

  @Override
  public void onEvent(Event event, Object source, Object data) {
    XWikiDocument document = (XWikiDocument) source;
    XWikiContext context = (XWikiContext) data;

    try {
      String lastAuthorEmail = context.getWiki().getDocument(document.getAuthorReference(), context)
          .getXObject(USER_CLASS_REFERENCE).getStringValue("email");

      // there is no destination email => stop here
      if (StringUtils.isBlank(lastAuthorEmail)) {
        return;
      }

      BaseObject commentObject = document.getXObject(COMMENT_CLASS_REFERENCE);

      // there is no comment !!!
      if (commentObject == null) {
        return;
      }

      // Get comment
      String comment = commentObject.getStringValue("comment");

      // Step 1: Create a JavaMail Session
      // ... with authentication:
      Session session = Session.getInstance(configuration.getAllProperties(), new XWikiAuthenticator(configuration));

      // Step 2: Create the Message to send
      MimeMessage message = new MimeMessage(session);
      if (event instanceof CommentAddedEvent) {
        message.setSubject("[XWiki] Comment added to " + document.toString());
      } else {
        message.setSubject("[XWiki] Comment updated on " + document.toString());
      }
      message.addRecipient(RecipientType.TO, new InternetAddress(lastAuthorEmail));

      // Step 3: Add the Message Body
      Multipart multipart = new MimeMultipart("mixed");
      MimeBodyPart bodyPart = new MimeBodyPart();
      bodyPart.setContent(comment, "text/plain");
      multipart.addBodyPart(bodyPart);
      message.setContent(multipart);

      // Step 4 : Send the mail asynchronously with a Database Mail Listener
      mailSender.sendAsynchronously(Arrays.asList(message), session, databaseMailListener);
    } catch (Exception e) {
      this.logger.error("Failure in comment listener", e);
    }

  }
}
