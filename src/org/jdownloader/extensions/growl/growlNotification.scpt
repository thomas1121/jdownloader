FasdUAS 1.101.10   ��   ��    l      ����  i         I     �� ��
�� .aevtoappnull  �   � ****  o      ���� 0 argv  ��    O     k  	  k    j 
 
     l   ��������  ��  ��        r    
    n        4    �� 
�� 
cobj  m    ����   o    ���� 0 argv    o      ���� 0 varheadline varHeadline      r        n        4    �� 
�� 
cobj  m    ����   o    ���� 0 argv    o      ���� 0 
varmessage 
varMessage      r        n         4    �� !
�� 
cobj ! m    ����    o    ���� 0 argv    o      ���� "0 varnotification varNotification   " # " l   ��������  ��  ��   #  $ % $ l   �� & '��   & 1 + Make a list of all the notification types     ' � ( ( V   M a k e   a   l i s t   o f   a l l   t h e   n o t i f i c a t i o n   t y p e s   %  ) * ) l   �� + ,��   + ' ! that this script will ever send:    , � - - B   t h a t   t h i s   s c r i p t   w i l l   e v e r   s e n d : *  . / . r    " 0 1 0 l 	    2���� 2 J      3 3  4 5 4 m     6 6 � 7 7 " D o w n l o a d   c o m p l e t e 5  8 9 8 m     : : � ; ; , A l l   d o w n l o a d s   f i n i s h e d 9  < = < m     > > � ? ?   P a c k a g e   f i n i s h e d =  @ A @ m     B B � C C  P r o g r a m s t a r t A  D�� D m     E E � F F  D o w n l o a d   f a i l e d��  ��  ��   1 l      G���� G o      ���� ,0 allnotificationslist allNotificationsList��  ��   /  H I H l  # #��������  ��  ��   I  J K J l  # #�� L M��   L ( " Make a list of the notifications     M � N N D   M a k e   a   l i s t   o f   t h e   n o t i f i c a t i o n s   K  O P O l  # #�� Q R��   Q - ' that will be enabled by default.          R � S S N   t h a t   w i l l   b e   e n a b l e d   b y   d e f a u l t .             P  T U T l  # #�� V W��   V 9 3 Those not enabled by default can be enabled later     W � X X f   T h o s e   n o t   e n a b l e d   b y   d e f a u l t   c a n   b e   e n a b l e d   l a t e r   U  Y Z Y l  # #�� [ \��   [ 7 1 in the 'Applications' tab of the growl prefpane.    \ � ] ] b   i n   t h e   ' A p p l i c a t i o n s '   t a b   o f   t h e   g r o w l   p r e f p a n e . Z  ^ _ ^ r   # 0 ` a ` l 	 # , b���� b J   # , c c  d e d m   # $ f f � g g " D o w n l o a d   c o m p l e t e e  h i h m   $ % j j � k k , A l l   d o w n l o a d s   f i n i s h e d i  l m l m   % & n n � o o   P a c k a g e   f i n i s h e d m  p q p m   & ' r r � s s  P r o g r a m s t a r t q  t�� t m   ' * u u � v v  D o w n l o a d   f a i l e d��  ��  ��   a l      w���� w o      ���� 40 enablednotificationslist enabledNotificationsList��  ��   _  x y x l  1 1��������  ��  ��   y  z { z l  1 1��������  ��  ��   {  | } | l  1 1�� ~ ��   ~ &   Register our script with growl.     � � � @   R e g i s t e r   o u r   s c r i p t   w i t h   g r o w l . }  � � � l  1 1�� � ���   � 7 1 You can optionally (as here) set a default icon     � � � � b   Y o u   c a n   o p t i o n a l l y   ( a s   h e r e )   s e t   a   d e f a u l t   i c o n   �  � � � l  1 1�� � ���   � ' ! for this script's notifications.    � � � � B   f o r   t h i s   s c r i p t ' s   n o t i f i c a t i o n s . �  � � � I  1 N���� �
�� .registernull��� ��� null��   � �� � �
�� 
appl � l 	 5 8 ����� � m   5 8 � � � � �  j D o w n l o a d e r��  ��   � �� � �
�� 
anot � l 
 ; < ����� � o   ; <���� ,0 allnotificationslist allNotificationsList��  ��   � �� � �
�� 
dnot � l 
 ? B ����� � o   ? B���� 40 enablednotificationslist enabledNotificationsList��  ��   � �� ���
�� 
iapp � m   E H � � � � �  j D o w n l o a d e r��   �  � � � l  O O��������  ��  ��   �  � � � l  O O�� � ���   �  	Send a Notification...    � � � � . 	 S e n d   a   N o t i f i c a t i o n . . . �  � � � l  O O�� � ���   �   notify with name �    � � � � &   n o t i f y   w i t h   n a m e   � �  � � � l  O O�� � ���   �  	varTitle title �    � � � � " 	 v a r T i t l e   t i t l e   � �  � � � l  O O�� � ���   � # 	varDescription description �    � � � � : 	 v a r D e s c r i p t i o n   d e s c r i p t i o n   � �  � � � l  O O�� � ���   � 1 +	varFilename application name "jDownloader"    � � � � V 	 v a r F i l e n a m e   a p p l i c a t i o n   n a m e   " j D o w n l o a d e r " �  � � � l  O O��������  ��  ��   �  � � � I  O h���� �
�� .notifygrnull��� ��� null��   � �� � �
�� 
name � l 	 S T ����� � o   S T���� "0 varnotification varNotification��  ��   � �� � �
�� 
titl � l 	 W X ����� � o   W X���� 0 varheadline varHeadline��  ��   � �� � �
�� 
desc � l 	 [ \ ����� � o   [ \���� 0 
varmessage 
varMessage��  ��   � �� ���
�� 
appl � m   _ b � � � � �  j D o w n l o a d e r��   �  � � � l  i i��������  ��  ��   �  ��� � l  i i��������  ��  ��  ��   	 m      � �2                                                                                  GRRR  alis    �  Macintosh HD               œ�hH+   h9GrowlHelperApp.app                                              h9(��R�        ����  	                	Resources     œ�X      ��6p     h9 h9 h9 p� 7�  YMacintosh HD:Library:PreferencePanes:Growl.prefPane:Contents:Resources:GrowlHelperApp.app   &  G r o w l H e l p e r A p p . a p p    M a c i n t o s h   H D  LLibrary/PreferencePanes/Growl.prefPane/Contents/Resources/GrowlHelperApp.app  / ��  ��  ��       �� � ���   � ��
�� .aevtoappnull  �   � **** � �� ���� � ���
�� .aevtoappnull  �   � ****�� 0 argv  ��   � ���� 0 argv   �  ��������� 6 : > B E���� f j n r u���� ������� ����������� ���
�� 
cobj�� 0 varheadline varHeadline�� 0 
varmessage 
varMessage�� "0 varnotification varNotification�� �� ,0 allnotificationslist allNotificationsList�� 40 enablednotificationslist enabledNotificationsList
�� 
appl
�� 
anot
�� 
dnot
�� 
iapp�� 
�� .registernull��� ��� null
�� 
name
�� 
titl
�� 
desc
�� .notifygrnull��� ��� null�� l� h��k/E�O��l/E�O��m/E�O������vE�O����a �vE` O*a a a �a _ a a a  O*a �a �a �a a a  OPU ascr  ��ޭ