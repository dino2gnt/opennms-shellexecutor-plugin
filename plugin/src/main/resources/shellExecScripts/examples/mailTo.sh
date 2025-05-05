#!/usr/bin/env bash

# This is a shell script example for the shellexecutor plugin
# that sends an email to a target email address when executed.
# if you local MTA can send outbound email, this script should, too.

MAILTO="you@example.com"

case "$action" in
  "TRIGGER")
    PREFIX=""
    ;;
  "ACKNOWLEDGE")
    PREFIX="Acknowledged: "
    ;;
  "RESOLVE")
    PREFIX="Resolved: "
    ;;
  "DELETED")
    echo "Deleted Alarm, exiting..."
    exit 0;
    ;;
esac

#Build subject
SUBJ="${PREFIX}${logmessage}";

#For grammatical correctness on threshold message
if [ "x$ds" != "x" -a "x$trigger" != "x" ]; then # Only thresholds have the datasource and trigger set
   if [[ "$reductionKey" =~ "lowThresholdExceeded" ]]; then
      VIO="above";
   fi
   if [[ "$reductionKey" =~ "highThresholdExceeded" ]]; then
      VIO="below";
   fi
THRESH_MSG="A value of \"$value\" on datasource \"$ds\" violated the threshold limit of \"$threshold\" after \"$trigger\" samples.  The threshold will re-arm when the value is $VIO \"$rearm\".";
fi

#Build the email body
BODY="On ${nodeLabel}: ${logmessage}\n\n${THRESH_MSG}\n\n${description}\n\nReduction Key: ${reductionKey}\n\nAlarm link: $clientUrl";

#Send the email using heirloom mail or equivalent
if [ "x$logmessage" != "x" ]; then
    echo "Sending email";
    echo -e "${BODY}" | /usr/bin/mail -s "${SUBJ}" "${MAILTO}";
fi

exit 0;
