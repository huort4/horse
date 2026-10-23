import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {UI_FONT_FAMILY} from './typography';
import {uiColors} from './brand';
import {BrandMark} from '../terminal/BrandMark';

/**
 * Simple terminal demo for the offline/shell-only edition.
 * AI agent scenes removed.
 */
export function WelcomeDemo(): React.JSX.Element {
  return (
    <View accessible accessibilityLabel="Horus runs a real Linux terminal on your phone" testID="welcome-demo">
      <View style={styles.window}>
        <View style={styles.titleBar}>
          <View style={styles.lights}>
            <View style={[styles.light, styles.lightRed]} />
            <View style={[styles.light, styles.lightAmber]} />
            <View style={[styles.light, styles.lightGreen]} />
          </View>
          <Text style={styles.titleText}>shell · ~/workspace</Text>
        </View>
        <View style={styles.body}>
          <Text style={styles.prompt}>
            <Text style={styles.chevron}>{'> '}</Text>
            apk add git curl
          </Text>
          <Text style={styles.line}>Installing packages...</Text>
          <Text style={[styles.line, styles.lineDone]}>✓ git-2.45.2 installed</Text>
          <Text style={[styles.line, styles.lineDone]}>✓ curl-8.9.1 installed</Text>
          <Text style={styles.prompt}>
            <Text style={styles.chevron}>{'> '}</Text>
            <Text style={styles.cursor}>▌</Text>
          </Text>
        </View>
      </View>
      <View style={styles.agents}>
        <View style={styles.agent}>
          <BrandMark accessible={false} size={22} />
          <Text style={[styles.agentName, styles.agentNameActive]}>Bare terminal</Text>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  window: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, overflow: 'hidden'},
  titleBar: {alignItems: 'center', borderBottomColor: uiColors.borderSoft, borderBottomWidth: 1, flexDirection: 'row', paddingHorizontal: 12, paddingVertical: 9},
  lights: {flexDirection: 'row', gap: 5},
  light: {borderRadius: 4, height: 8, width: 8},
  lightRed: {backgroundColor: '#FF5F57'},
  lightAmber: {backgroundColor: '#FEBC2E'},
  lightGreen: {backgroundColor: '#28C840'},
  titleText: {color: uiColors.subdued, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginRight: 31, textAlign: 'center'},
  body: {minHeight: 118, paddingHorizontal: 14, paddingVertical: 12},
  prompt: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, lineHeight: 20},
  chevron: {color: uiColors.accent},
  cursor: {color: uiColors.accent},
  line: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 20},
  lineDone: {color: uiColors.accent},
  agents: {flexDirection: 'row', justifyContent: 'space-between', marginTop: 14},
  agent: {alignItems: 'center', flex: 1, gap: 6},
  agentName: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10},
  agentNameActive: {color: uiColors.ink},
});
